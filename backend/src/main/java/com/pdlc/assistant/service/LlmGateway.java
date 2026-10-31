package com.pdlc.assistant.service;

import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;
import com.pdlc.assistant.dto.ChatTurn;
import com.pdlc.assistant.dto.LlmRequest;
import com.pdlc.assistant.exception.LlmException;

/**
 * The only place that talks to OpenAI. Every call gets schema-enforced output parsed into
 * {@code schema}, a pre-flight input-size check, a hard output cap, one retry on bad output
 * and a token line in the log. Never call the OpenAI client from anywhere else.
 */
@Service
public class LlmGateway {

	private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

	private static final int MAX_ATTEMPTS = 2;

	private static final String RETRY_NOTE = "\n\nYour previous reply was not valid for the required JSON schema. "
			+ "Reply again with only a complete, valid JSON object.";

	private final OpenAIClient client;

	private final String defaultModel;

	private final int defaultMaxCompletionTokens;

	private final int maxInputTokens;

	public LlmGateway(@Lazy OpenAIClient client, @Value("${pdlc.llm.model}") String defaultModel,
			@Value("${pdlc.llm.max-completion-tokens}") int defaultMaxCompletionTokens,
			@Value("${pdlc.llm.max-input-tokens}") int maxInputTokens) {
		this.client = client;
		this.defaultModel = defaultModel;
		this.defaultMaxCompletionTokens = defaultMaxCompletionTokens;
		this.maxInputTokens = maxInputTokens;
	}

	public <T> T complete(LlmRequest request, Class<T> schema) {
		String model = request.model() != null ? request.model() : defaultModel;
		int maxOut = request.maxCompletionTokens() != null ? request.maxCompletionTokens() : defaultMaxCompletionTokens;

		int estimate = estimateTokens(request.system()) + estimateTokens(request.user());
		if (estimate > maxInputTokens) {
			throw new IllegalArgumentException("Input too large for '" + request.task() + "': ~" + estimate
					+ " tokens, limit " + maxInputTokens + ". Send a smaller slice.");
		}

		String user = request.user();
		Exception last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			StructuredChatCompletionCreateParams<T> params = ChatCompletionCreateParams.builder()
				.model(model)
				.maxCompletionTokens(maxOut)
				.addSystemMessage(request.system())
				.addUserMessage(user)
				.responseFormat(schema)
				.build();
			try {
				StructuredChatCompletion<T> completion = client.chat().completions().create(params);
				String usage = completion.usage()
					.map(u -> "prompt=" + u.promptTokens() + " completion=" + u.completionTokens())
					.orElse("usage=n/a");
				log.info("llm task={} model={} attempt={} estimate={} {}", request.task(), model, attempt, estimate,
						usage);
				// Parsing is lazy: a malformed or truncated reply throws here.
				return completion.choices()
					.stream()
					.flatMap(c -> c.message().content().stream())
					.findFirst()
					.orElseThrow(() -> new LlmException("Model returned no content (refusal or empty reply)"));
			}
			catch (OpenAIInvalidDataException | LlmException e) {
				last = e;
				log.warn("llm task={} attempt={} output did not match schema: {}", request.task(), attempt,
						e.getMessage());
				user = request.user() + RETRY_NOTE;
			}
			catch (OpenAIException e) {
				throw new LlmException("OpenAI call failed for '" + request.task() + "': " + e.getMessage(), e);
			}
		}
		throw new LlmException("Model output did not match the schema after " + MAX_ATTEMPTS + " attempts for '"
				+ request.task() + "'", last);
	}

	/**
	 * Streams a plain-text reply, calling {@code onDelta} for each piece as it arrives. Same input cap, output cap
	 * and token log as {@link #complete}, but no retry (text already shown cannot be taken back). Stops and closes
	 * the upstream stream as soon as {@code cancelled} turns true, so a Stop click stops the token spend.
	 */
	public void stream(String task, String system, List<ChatTurn> turns, Consumer<String> onDelta,
			BooleanSupplier cancelled) {
		int estimate = estimateTokens(system) + turns.stream().mapToInt(t -> estimateTokens(t.content())).sum();
		if (estimate > maxInputTokens) {
			throw new IllegalArgumentException("Input too large for '" + task + "': ~" + estimate + " tokens, limit "
					+ maxInputTokens + ". Send a shorter message.");
		}
		ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
			.model(defaultModel)
			.maxCompletionTokens(defaultMaxCompletionTokens)
			.addSystemMessage(system)
			.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
		for (ChatTurn turn : turns) {
			if ("assistant".equals(turn.role())) {
				builder.addAssistantMessage(turn.content());
			}
			else {
				builder.addUserMessage(turn.content());
			}
		}

		String usage = "usage=n/a";
		String ended = " (stopped early: client gone or error)";
		int pieces = 0;
		try (StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(builder.build())) {
			Iterator<ChatCompletionChunk> chunks = stream.stream().iterator();
			while (chunks.hasNext()) {
				if (cancelled.getAsBoolean()) {
					ended = " (stopped by client)";
					return;
				}
				ChatCompletionChunk chunk = chunks.next();
				for (var choice : chunk.choices()) {
					if (choice.delta().content().isPresent()) {
						onDelta.accept(choice.delta().content().get());
						pieces++;
					}
				}
				if (chunk.usage().isPresent()) {
					usage = "prompt=" + chunk.usage().get().promptTokens() + " completion="
							+ chunk.usage().get().completionTokens();
				}
			}
			ended = "";
		}
		catch (OpenAIException e) {
			throw new LlmException("OpenAI call failed for '" + task + "': " + e.getMessage(), e);
		}
		finally {
			// logged on every exit path; a stopped stream has no usage chunk, so show pieces received instead
			log.info("llm task={} model={} stream estimate={} {} pieces={}{}", task, defaultModel, estimate, usage,
					pieces, ended);
		}
	}

	/** Rough pre-flight estimate: about 4 characters per token. */
	static int estimateTokens(String text) {
		return text == null ? 0 : (text.length() + 3) / 4;
	}

}

package com.pdlc.assistant.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.pdlc.assistant.dto.ChatRequest;
import com.pdlc.assistant.dto.ImportProposal;
import com.pdlc.assistant.exception.DiagramException;
import com.pdlc.assistant.exception.LlmException;
import com.pdlc.assistant.dto.ProgressStep;
import com.pdlc.assistant.service.ChatService;
import com.pdlc.assistant.service.DiscoveryService;

/**
 * Streaming chat over Server-Sent Events. Events (JSON data): {@code status {text}}, {@code token {text}},
 * {@code proposal {patches, files, warnings}}, then {@code done} or {@code error {message}}.
 * If the client disconnects (Stop), the reply is cancelled and the upstream LLM stream is closed.
 */
@RestController
public class ChatController {

	private static final Logger log = LoggerFactory.getLogger(ChatController.class);

	private static final long TIMEOUT_MS = 180_000;

	private final ChatService chat;

	private final DiscoveryService discovery;

	private final ExecutorService executor;

	public ChatController(ChatService chat, DiscoveryService discovery,
			@Qualifier("chatExecutor") ExecutorService executor) {
		this.discovery = discovery;
		this.chat = chat;
		this.executor = executor;
	}

	/** Steps of the product interview with their state; empty once requirements exist. */
	@GetMapping("/api/projects/{id}/interview")
	public Map<String, Object> interview(@PathVariable String id) {
		return Map.of("steps", discovery.progress(id));
	}

	@PostMapping(value = "/api/projects/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter chat(@PathVariable String id, @RequestBody ChatRequest request) {
		SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
		AtomicBoolean cancelled = new AtomicBoolean();
		emitter.onCompletion(() -> cancelled.set(true));
		emitter.onTimeout(() -> cancelled.set(true));
		emitter.onError(e -> cancelled.set(true));
		executor.execute(() -> run(id, request, emitter, cancelled));
		return emitter;
	}

	private void run(String projectId, ChatRequest request, SseEmitter emitter, AtomicBoolean cancelled) {
		try {
			chat.respond(projectId, request, new ChatService.Events() {
				@Override
				public void status(String text) {
					send(emitter, cancelled, "status", Map.of("text", text));
				}

				@Override
				public void token(String text) {
					send(emitter, cancelled, "token", Map.of("text", text));
				}

				@Override
				public void proposal(ImportProposal proposal) {
					send(emitter, cancelled, "proposal", proposal);
				}

				@Override
				public void docs(List<String> files) {
					send(emitter, cancelled, "docs", Map.of("files", files));
				}

				@Override
				public void progress(List<ProgressStep> steps) {
					send(emitter, cancelled, "progress", Map.of("steps", steps));
				}
			}, cancelled::get);
			if (!cancelled.get()) {
				send(emitter, cancelled, "done", Map.of());
			}
		}
		catch (RuntimeException e) {
			if (!cancelled.get()) {
				send(emitter, cancelled, "error", Map.of("message", describe(e)));
			}
		}
		finally {
			emitter.complete();
		}
	}

	private static void send(SseEmitter emitter, AtomicBoolean cancelled, String event, Object data) {
		try {
			emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException e) {
			// the client went away (Stop, closed tab): stop producing
			cancelled.set(true);
			throw new UncheckedIOException(new IOException("client disconnected", e));
		}
	}

	private static String describe(RuntimeException e) {
		if (e instanceof IllegalArgumentException || e instanceof LlmException || e instanceof DiagramException) {
			return e.getMessage();
		}
		log.error("Chat failed", e);
		return "Something went wrong while answering. Check the backend log.";
	}

}

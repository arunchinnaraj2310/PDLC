package com.pdlc.assistant.service;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pdlc.assistant.dto.ChatTurn;
import com.pdlc.assistant.dto.IntentDraft;
import com.pdlc.assistant.dto.IntentDraft.Intent;
import com.pdlc.assistant.dto.LlmRequest;
import com.pdlc.assistant.exception.LlmException;

/**
 * Decides whether a chat message is a design change, a product-level change or a question. A tiny schema-enforced call on the
 * router model (cheap, about 150 tokens). If it fails the message is treated as a question, which writes nothing.
 */
@Service
public class IntentRouter {

	private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

	private static final int MAX_COMPLETION_TOKENS = 20;

	private static final int CONTEXT_TURNS = 2;

	private static final int CONTEXT_CHARS = 300;

	private static final String SYSTEM = """
			Classify the latest message sent to a product-design assistant.
			change: the user asks to add, remove, rename, replace, connect or otherwise modify something in the \
			requirements or the architecture (examples: "add Redis caching", "we need OAuth2 login", "remove the \
			email provider", "make the API talk to a message queue", "also support admins").
			question: anything else, such as asking what exists, what something depends on, why, how something \
			works, or small talk. Examples: "Which components does the API depend on?", "Why is there a cache?", \
			"What does CMP-003 do?". A message that only asks about the design is a question even when it \
			mentions components.
				product: the user states or changes the product itself, at business level rather than technical: its \
				vision or problem, who it is for, goals or success metrics, what is in or out of scope, a persona or a \
				user story (examples: "our users are now small clinics", "the goal is to cut churn by 10%", \
				"drop the mobile app from scope", "add a story for password reset by email").
				When in doubt choose question: a wrong "question" is harmless, a wrong "change" or "product" \
			is not.
			Earlier messages are context only: classify the latest message.""";

	/** Plain wh-/yes-no questions need no model call: they are questions. */
	private static final Pattern PLAIN_QUESTION = Pattern.compile(
			"^(which|what|who|whom|whose|where|when|why|how|does|do|is|are|was|were)\\b.*\\?\\s*$",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private final LlmGateway llm;

	private final String routerModel;

	public IntentRouter(LlmGateway llm, @Value("${pdlc.llm.router-model}") String routerModel) {
		this.llm = llm;
		this.routerModel = routerModel;
	}

	public Intent classify(String message, List<ChatTurn> recent) {
		if (PLAIN_QUESTION.matcher(message.strip()).matches()) {
			return Intent.question;
		}
		StringBuilder input = new StringBuilder();
		recent.subList(Math.max(0, recent.size() - CONTEXT_TURNS), recent.size())
			.forEach(t -> input.append(t.role()).append(": ").append(trim(t.content())).append('\n'));
		input.append("Latest message: ").append(message);
		try {
			Intent intent = llm.complete(
					new LlmRequest("intent", SYSTEM, input.toString(), routerModel, MAX_COMPLETION_TOKENS),
					IntentDraft.class).intent;
			return intent == null ? Intent.question : intent;
		}
		catch (LlmException e) {
			log.warn("Intent routing failed, treating the message as a question: {}", e.getMessage());
			return Intent.question;
		}
	}

	private static String trim(String text) {
		return text.length() > CONTEXT_CHARS ? text.substring(0, CONTEXT_CHARS) + "…" : text;
	}

}

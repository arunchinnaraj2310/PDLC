package com.pdlc.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.pdlc.assistant.dto.ChatRequest;
import com.pdlc.assistant.dto.ChatTurn;
import com.pdlc.assistant.dto.ImportProposal;
import com.pdlc.assistant.dto.IntentDraft;
import com.pdlc.assistant.dto.ProgressStep;
import com.pdlc.assistant.model.MdEntry;

/**
 * One chat message in, a stream of events out. Routes: a pasted requirements document goes through the import
 * flow; until requirements exist, every message is part of the product interview; "generate requirements" turns the product documents into requirements and design; otherwise the
 * intent router picks between a product change (rewrites the product documents), a design change, or a question
 * answered by the model token by token. Proposals are never written here; product documents are written by the
 * interview and announced with a {@code docs} event.
 */
@Service
public class ChatService {

	/** Where a reply goes; the controller turns these into SSE events. */
	public interface Events {

		void status(String text);

		void token(String text);

		void proposal(ImportProposal proposal);

		/** Product documents (project-relative paths) were written; the UI should reload them. */
		void docs(List<String> files);

		/** Where the product interview stands after this message. */
		void progress(List<ProgressStep> steps);

	}

	private static final int MAX_HISTORY_TURNS = 6;

	private static final int MAX_TURN_CHARS = 2000;

	private static final int MAX_MODEL_CHARS = 12000;

	/** In "auto" mode a message is treated as a pasted document at this size or with this many headings. */
	private static final int DOCUMENT_MIN_CHARS = 800;

	private static final int DOCUMENT_MIN_HEADINGS = 2;

	/** "generate requirements", "move on to the design", ... */
	private static final Pattern GENERATE = Pattern.compile(
			"^\\s*(please\\s+)?(generate|create|draft|build|move on to|move to|proceed to|go to|continue to)\\b"
					+ ".{0,40}\\b(requirements?|design)\\b.{0,40}$",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final String SYSTEM = """
			You are the assistant inside a product-development tool used by one admin. Answer questions about the \
			project's requirements and design using only the project model below. Be concise, use markdown, and \
			cite ids such as REQ-001 and CMP-002. You do not edit the project in this reply: when the user asks for a \
			change, a separate step turns it into a proposal they can review and apply, so just answer questions. \
			If the model does not contain the answer, say so instead of guessing.""";

	private final RequirementsImportService imports;

	private final DesignChangeService changes;

	private final DiscoveryService discovery;

	private final IntentRouter router;

	private final ProjectService projects;

	private final LlmGateway llm;

	public ChatService(RequirementsImportService imports, DesignChangeService changes, DiscoveryService discovery,
			IntentRouter router, ProjectService projects, LlmGateway llm) {
		this.imports = imports;
		this.changes = changes;
		this.discovery = discovery;
		this.router = router;
		this.projects = projects;
		this.llm = llm;
	}

	public void respond(String projectId, ChatRequest request, Events events, BooleanSupplier cancelled) {
		String message = request.message() == null ? "" : request.message().strip();
		if (message.isEmpty()) {
			throw new IllegalArgumentException("The message is empty");
		}
		boolean hasRequirements = !projects.entries(projectId, ProjectService.REQ_FILE).isEmpty();
		boolean interviewing = !hasRequirements;
		List<ChatTurn> history = recent(request.history());

		// While interviewing, long answers are answers, not documents: only an explicit mode or real structure counts.
		if (looksLikeRequirementsDocument(request.mode(), message, interviewing)) {
			events.status("Treating this as a requirements document.");
			events.proposal(imports.propose(projectId, message, events::status));
			return;
		}
		if (GENERATE.matcher(message).matches()) {
			generate(projectId, hasRequirements, history, events);
			return;
		}
		if (interviewing) {
			discovery.respond(projectId, message, history, events, cancelled);
			return;
		}

		events.status("Understanding your message…");
		IntentDraft.Intent intent = router.classify(message, history);
		if (intent == IntentDraft.Intent.product) {
			events.status("This is about the product itself.");
			discovery.respond(projectId, message, history, events, cancelled);
			return;
		}
		if (intent == IntentDraft.Intent.change) {
			events.status("This is a change to the design.");
			events.proposal(changes.propose(projectId, message, history, events::status));
			return;
		}

		events.status("Thinking…");
		List<ChatTurn> turns = new ArrayList<>(history);
		turns.add(new ChatTurn("user", message));
		llm.stream("chat", SYSTEM + "\n\nProject model:\n" + modelSummary(projectId), turns, events::token, cancelled);
	}

	/** Product documents to requirements and design: a first import, or a re-alignment once those exist. */
	private void generate(String projectId, boolean hasRequirements, List<ChatTurn> history, Events events) {
		String documents = discovery.combinedDocuments(projectId);
		if (documents.isEmpty()) {
			throw new IllegalArgumentException("The product documents are empty. Answer a few questions first.");
		}
		if (hasRequirements) {
			events.status("Aligning the requirements and design with the product documents…");
			events.proposal(changes.propose(projectId,
					"Align the requirements and design with these product documents. Change only what differs.\n\n"
							+ documents,
					history, events::status));
			return;
		}
		events.status("Generating requirements and design from the product documents.");
		events.proposal(imports.propose(projectId, documents, events::status));
	}

	static boolean looksLikeRequirementsDocument(String mode, String message, boolean interviewing) {
		if ("requirements".equals(mode)) {
			return true;
		}
		long headings = message.lines().filter(line -> line.startsWith("#")).count();
		return headings >= DOCUMENT_MIN_HEADINGS || (!interviewing && message.length() >= DOCUMENT_MIN_CHARS);
	}

	private static List<ChatTurn> recent(List<ChatTurn> history) {
		if (history == null) {
			return List.of();
		}
		List<ChatTurn> valid = history.stream()
			.filter(t -> t != null && t.content() != null && !t.content().isBlank())
			.filter(t -> "user".equals(t.role()) || "assistant".equals(t.role()))
			.map(t -> new ChatTurn(t.role(), t.content().length() > MAX_TURN_CHARS
					? t.content().substring(0, MAX_TURN_CHARS) + "…" : t.content()))
			.toList();
		return valid.subList(Math.max(0, valid.size() - MAX_HISTORY_TURNS), valid.size());
	}

	/** Compact text listing of the current REQ and CMP entries: the slice the model answers from. */
	private String modelSummary(String projectId) {
		String reqs = projects.entries(projectId, ProjectService.REQ_FILE)
			.stream()
			.map(e -> e.id() + ": " + e.text())
			.collect(Collectors.joining("\n"));
		String cmps = projects.entries(projectId, ProjectService.CMP_FILE).stream().map(ChatService::describe)
			.collect(Collectors.joining("\n"));
		String summary = "Requirements:\n" + (reqs.isEmpty() ? "(none yet)" : reqs) + "\n\nComponents:\n"
				+ (cmps.isEmpty() ? "(none yet)" : cmps);
		return summary.length() <= MAX_MODEL_CHARS ? summary
				: summary.substring(0, MAX_MODEL_CHARS) + "\n(model truncated)";
	}

	private static String describe(MdEntry c) {
		StringBuilder out = new StringBuilder(c.id() + ": " + c.text());
		c.attrs().forEach((k, v) -> out.append(" | ").append(k).append(": ").append(v));
		return out.toString();
	}

}

package com.pdlc.assistant.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pdlc.assistant.dto.ChatTurn;
import com.pdlc.assistant.dto.DocSyncDraft;
import com.pdlc.assistant.dto.LlmRequest;
import com.pdlc.assistant.dto.ProgressStep;
import com.pdlc.assistant.dto.StepCheck;
import com.pdlc.assistant.model.InterviewStep;
import com.pdlc.assistant.model.MdSections;
import com.pdlc.assistant.model.NarrativeDoc;

/**
 * The product interview. Each message goes through two model calls: a schema-enforced one that rewrites the
 * product documents (intent, personas, user stories) from what the user said, then a streamed reply that
 * acknowledges it and asks the next question. Once requirements exist the same path serves product changes:
 * the documents are updated first, then a design-change proposal for the requirements is built from the change.
 */
@Service
public class DiscoveryService {

	private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

	private static final Pattern STORY_ID = Pattern.compile("US-([0-9]+)");

	private static final Pattern STORY_PREFIX = Pattern.compile("^([-*] +)?(US-[0-9]+:? *)?");

	private static final int SYNC_MAX_COMPLETION_TOKENS = 2000;

	private static final String ALL_DONE = "All sections are complete: product intent, personas and user stories. "
			+ "Type **generate requirements** to turn them into requirements and a design for review.";

	private static final String SYNC_SYSTEM = """
			You maintain three product documents for a product-development tool: Product Intent (PRODUCT_INTENT), \
			Personas (PERSONAS) and User Stories (USER_STORIES). The admin is being interviewed; each message may \
			answer a question, add detail or change an earlier decision. Turn what the admin said, in the \
			conversation and in the latest message, into small edits to the documents.
			Each edit has: file, section (a heading, no hashes), op, text.
			- set: replace a whole section with one paragraph. Use it only for Vision and Problem.
			- add: append ONE bullet to a section; the section is created if missing. One fact per edit, one line, \
			no list markers, no headings. For USER_STORIES write only 'As a <persona>, I want <goal> so that \
			<benefit>' (ids are added for you).
			- remove: delete the bullet that starts with text. For a story use its id, for example US-002.
			- removeSection: delete a whole persona section that no longer applies.
			Reuse the existing section for the same persona; never create a second, near-duplicate persona (for \
			example 'Freelancer' and 'Solo Freelancer'). Scope bullets go in section Scope and start with \
			'In scope:' or 'Out of scope:'.
			A value that is replaced ('X, not Y') needs a remove for every bullet that mentions Y, in every section \
			and document (Vision text, Target users, Goals, personas, stories), plus an add for X where it belongs. \
			Check each section of every document for leftovers of what was dropped.
			Never repeat what a document already says and never copy a document into an edit. A change of mind is \
			a remove plus an add, and apply it to every document that now disagrees (a story for a dropped feature, \
			a persona that no longer applies).
			Record only what the admin said or clearly implied. Never invent facts, goals, metrics, scope \
			exclusions or constraints. Things still unknown go as bullets under 'Open questions' in \
			PRODUCT_INTENT; remove a question once it is answered.
			Create persona and story sections (section = the persona's name) only once the admin has said \
			something about users or behaviour. If the latest message holds no project information (a question, a \
			greeting, 'yes', 'ok'), return no edits.
			changeNote: one short sentence naming what changed; empty when nothing changed.
			An explicit 'none' (for example no constraints) is an answer: record it as one bullet such as 'No \
			constraints stated'.
			Documents and their sections:
			""" + describeDocs();

	private static final String STEP_SYSTEM = """
			You judge whether one step of a product interview has enough information to move on. You get the step, \
			its 'enough when' test, the product document and the admin's latest message. Answer met=true only when \
			the document's content for this step satisfies the test AND rests on what the admin actually said. Be \
			strict: vague or generic content ('easy to use', 'good', 'fast') does not pass, and a test with several \
			parts needs every part. If the admin's latest message confirms or accepts what is already written for \
			the step, it passes. If met is false, missing names in a few words the one most important thing still \
			needed; if true, missing is empty.""";

	private static final String ASK_SYSTEM = """
			You are a product discovery partner inside a product-development tool, interviewing the admin to build \
			three documents: Product Intent, Personas and User Stories. The documents were just updated from the \
			admin's last message. The interview goes section by section and only moves on once a section has enough \
			information; the interview status below says where it stands. Reply in short markdown:
			1. One short sentence on what you captured from the last message (skip it if nothing). Do not announce \
			completed sections yourself; that line is added for you. If the admin asked you something, answer it \
			briefly first.
			2. Work ONLY on the current section. Ask exactly one question that gets what is still needed for it, in \
			plain language with a short example answer. If the section already has some content from earlier \
			messages, say in one line what is recorded and ask for what is missing or whether it is right. If the \
			last answer was vague or partial, ask one follow-up on the same section instead of moving on. Never \
			ask about later sections and never ask again about something the admin already answered.
			3. Only when the status says there is no current section, say all sections are complete and tell the \
			admin to type "generate requirements" to turn them into requirements and a design for review. Ask no \
			more questions.
			Never write the documents or lists of requirements in this reply.""";

	private static final String REVISE_SYSTEM = """
			You are the assistant inside a product-development tool. The product documents (Product Intent, \
			Personas, User Stories) may have just been updated from the admin's message; the updated files are listed \
			below. In one to three sentences confirm what changed and in which document. If nothing changed, answer \
			the message briefly from the documents. Do not start an interview. When documents changed, matching \
			requirement and design changes are proposed right after this reply for the admin to review, so you may \
			say that. Never write requirements, lists or headings yourself: reply in at most three sentences.""";

	private final ProjectService projects;

	private final LlmGateway llm;

	private final DesignChangeService changes;

	/** Model for the document-editing call, where quality matters most; null means the default model. */
	private final String discoveryModel;

	public DiscoveryService(ProjectService projects, LlmGateway llm, DesignChangeService changes,
			@Value("${pdlc.llm.discovery-model:}") String discoveryModel) {
		this.discoveryModel = discoveryModel.isBlank() ? null : discoveryModel;
		this.projects = projects;
		this.llm = llm;
		this.changes = changes;
	}

	public void respond(String projectId, String message, List<ChatTurn> chatHistory, ChatService.Events events,
			BooleanSupplier cancelled) {
		// the "✅ X is captured" lines are added by us; shown back to the model they get copied and repeated
		List<ChatTurn> history = chatHistory.stream()
			.map(t -> new ChatTurn(t.role(), t.content().replaceAll("(?m)^✅.*$", "").strip()))
			.filter(t -> !t.content().isEmpty())
			.toList();
		boolean revising = !projects.entries(projectId, ProjectService.REQ_FILE).isEmpty();

		events.status("Capturing this in the product documents…");
		DocSyncDraft draft = llm.complete(new LlmRequest("discovery-sync", SYNC_SYSTEM,
				syncInput(projectId, message, history), discoveryModel, SYNC_MAX_COMPLETION_TOKENS), DocSyncDraft.class);
		String note = draft.changeNote == null ? "" : draft.changeNote.strip();

		List<NarrativeDoc> written = merge(projectId, draft.edits, note);
		if (!written.isEmpty()) {
			events.docs(written.stream().map(NarrativeDoc::path).toList());
		}
		String brief = "";
		if (!revising) {
			events.status("Checking whether this section has enough information…");
			StepResult result = assess(projectId, message, history);
			List<InterviewStep> finished = result.finished();
			Set<InterviewStep> done = projects.completedSteps(projectId);
			events.progress(progress(done));
			// announced here, not by the model: it mislabelled which sections were complete
			if (!finished.isEmpty()) {
				events.token("✅ " + finished.stream().map(InterviewStep::label).collect(Collectors.joining(", "))
						+ (finished.size() == 1 ? " is" : " are") + " captured.\n\n");
				if (firstOpen(done) == null) {
					events.token(ALL_DONE);
					return;
				}
			}
			brief ="\n\nInterview status:\n" + stepStatus(done) + "\nSections completed this turn, already announced to the admin (do not mention or repeat): "
					+ (finished.isEmpty() ? "none" : finished.stream().map(InterviewStep::label).collect(Collectors.joining(", ")))
					+ "\n" + currentSection(done, result.missing());
		}
		log.info("discovery project={} revising={} updated={}", projectId, revising, written);
		if (cancelled.getAsBoolean()) {
			return;
		}

		events.status("Thinking…");
		String updated = written.isEmpty() ? "none"
				: written.stream().map(NarrativeDoc::path).collect(Collectors.joining(", "));
		// the interview status goes last: it is what the reply must follow, so it should be the freshest thing read
		String system = (revising ? REVISE_SYSTEM : ASK_SYSTEM) + "\n\nUpdated this turn: " + updated
				+ "\n\nCurrent documents:\n" + documents(projectId) + brief;
		List<ChatTurn> turns = new ArrayList<>(history);
		turns.add(new ChatTurn("user", message));
		llm.stream("discovery", system, turns, events::token, cancelled);
		if (cancelled.getAsBoolean() || !revising || written.isEmpty()) {
			return;
		}

		// Requirements and design exist: carry the product change down into them, as a proposal to review.
		events.status("Checking requirements and design against the updated documents…");
		try {
			events.proposal(changes.propose(projectId, "The product documents changed" + (note.isEmpty() ? "" : ": " + note)
					+ ". Update the requirements and design to match. Change only what this affects.", history,
					events::status));
		}
		catch (IllegalArgumentException e) {
			events.token("\n\n_No requirement or design change was proposed: " + e.getMessage() + "_");
		}
	}

	/** Where the interview stands, for the progress strip; empty once requirements exist (the interview is over). */
	public List<ProgressStep> progress(String projectId) {
		if (!projects.entries(projectId, ProjectService.REQ_FILE).isEmpty()) {
			return List.of();
		}
		return progress(projects.completedSteps(projectId));
	}

	private static List<ProgressStep> progress(Set<InterviewStep> done) {
		InterviewStep current = firstOpen(done);
		List<ProgressStep> steps = new ArrayList<>();
		for (InterviewStep step : InterviewStep.values()) {
			steps.add(new ProgressStep(step.name(), step.label(),
					done.contains(step) ? "done" : step == current ? "current" : "todo"));
		}
		return steps;
	}

	private static InterviewStep firstOpen(Set<InterviewStep> done) {
		for (InterviewStep step : InterviewStep.values()) {
			if (!done.contains(step)) {
				return step;
			}
		}
		return null;
	}

	/** Outcome of the step check: the steps completed by this message and what the current step still needs. */
	private record StepResult(List<InterviewStep> finished, String missing) {
	}

	/**
	 * Decides whether the current step now has enough information, with a small focused model call. Steps whose
	 * section this turn's edits emptied are reopened first. At most the current step completes per message, so
	 * information the admin volunteered early is confirmed in a later turn instead of being waved through unseen.
	 */
	private StepResult assess(String projectId, String message, List<ChatTurn> history) {
		Set<InterviewStep> done = new LinkedHashSet<>(projects.completedSteps(projectId));
		done.removeIf(step -> !hasContent(projectId, step));
		InterviewStep current = firstOpen(done);
		List<InterviewStep> finished = new ArrayList<>();
		String missing = "";
		if (current != null) {
			if (hasContent(projectId, current)) {
				StringBuilder input = new StringBuilder("Step: ").append(current.label()).append("\nEnough when: ")
					.append(current.enough())
					.append("\n\nDocument (judge only the section or content for this step):\n")
					.append(projects.narrativeBody(projectId, current.doc()));
				history.stream()
					.skip(Math.max(0, history.size() - 2))
					.forEach(t -> input.append("\n").append(t.role()).append(": ").append(t.content()));
				input.append("\n\nLatest admin message:\n").append(message);
				StepCheck check = llm.complete(
						new LlmRequest("interview-step", STEP_SYSTEM, input.toString(), discoveryModel, 120),
						StepCheck.class);
				if (check.met) {
					done.add(current);
					finished.add(current);
				}
				else {
					missing = check.missing == null ? "" : check.missing.strip();
				}
				log.info("interview project={} step={} met={} missing='{}'", projectId, current, check.met, missing);
			}
			else {
				missing = "a first answer for this section";
			}
		}
		projects.setCompletedSteps(projectId, done);
		return new StepResult(finished, missing);
	}

	private boolean hasContent(String projectId, InterviewStep step) {
		return step.hasContent(projects.narrativeBody(projectId, step.doc()));
	}

	private static String stepStatus(Set<InterviewStep> done) {
		StringBuilder out = new StringBuilder();
		for (InterviewStep step : InterviewStep.values()) {
			out.append("- ").append(step.name()).append(" (").append(step.label()).append(") ")
				.append(done.contains(step) ? "[done]" : "[open]")
				.append(": enough when ").append(step.enough()).append('\n');
		}
		return out.toString();
	}

	private static String currentSection(Set<InterviewStep> done, String missing) {
		InterviewStep current = firstOpen(done);
		if (current == null) {
			return "There is no current section: every section is complete.";
		}
		String need = missing == null || missing.isBlank() ? "everything in the test above" : missing.strip();
		return "Current section: " + current.label() + ". Still needed: " + need + ".";
	}

	/** The three documents as one markdown text, e.g. for "generate requirements"; blank when none is started. */
	public String combinedDocuments(String projectId) {
		StringBuilder out = new StringBuilder();
		for (NarrativeDoc doc : NarrativeDoc.values()) {
			String body = projects.narrativeBody(projectId, doc);
			if (!body.isEmpty()) {
				out.append(body).append("\n\n");
			}
		}
		return out.toString().strip();
	}

	/** Applies the edits to their documents and writes each document that actually changed. */
	private List<NarrativeDoc> merge(String projectId, List<DocSyncDraft.Edit> edits, String note) {
		Map<NarrativeDoc, MdSections> docs = new LinkedHashMap<>();
		Map<NarrativeDoc, String> before = new HashMap<>();
		int[] nextStory = { 0 };
		for (DocSyncDraft.Edit edit : edits == null ? List.<DocSyncDraft.Edit>of() : edits) {
			if (edit.file == null || edit.op == null || edit.section == null || edit.section.isBlank()) {
				continue;
			}
			String section = edit.section.strip().replaceFirst(":$", "");
			String text = edit.text == null ? "" : edit.text.strip();
			if (edit.file == NarrativeDoc.PRODUCT_INTENT && section.matches("(?i)(in|out of) scope")) {
				// the model sometimes names the scope list as the section; it is a bullet prefix under Scope
				String prefix = section.toLowerCase().startsWith("in") ? "In scope: " : "Out of scope: ";
				text = prefix + text.replaceFirst("(?i)^(in|out of) scope: *", "");
				section = "Scope";
			}
			if (edit.file == NarrativeDoc.PRODUCT_INTENT) {
				// the model also uses the step's short label ("Goals") for the section ("Goals and success metrics")
				for (InterviewStep step : InterviewStep.values()) {
					if (step.section() != null && (step.label().equalsIgnoreCase(section)
							|| step.name().replace('_', ' ').equalsIgnoreCase(section))) {
						section = step.section();
					}
				}
			}
			String heading = section;
			boolean known = edit.file.sections().isEmpty()
					|| edit.file.sections().stream().anyMatch(h -> h.equalsIgnoreCase(heading));
			if (!known || (edit.op == DocSyncDraft.Op.removeSection && !edit.file.sections().isEmpty())) {
				log.warn("discovery ignored an edit to unknown section '{}' of {}", edit.section, edit.file);
				continue;
			}
			MdSections doc = docs.computeIfAbsent(edit.file, f -> {
				String body = projects.narrativeBody(projectId, f);
				before.put(f, MdSections.parse(body).render(f.title()));
				if (f == NarrativeDoc.USER_STORIES) {
					Matcher ids = STORY_ID.matcher(body);
					while (ids.find()) {
						nextStory[0] = Math.max(nextStory[0], Integer.parseInt(ids.group(1)));
					}
				}
				return MdSections.parse(body);
			});
			switch (edit.op) {
				case set -> doc.set(heading, text, edit.file.sections());
				case add -> {
					for (String line : text.lines().toList()) {
						String bullet = line.strip();
						if (edit.file == NarrativeDoc.USER_STORIES && !bullet.isEmpty()) {
							bullet = "US-%03d: %s".formatted(++nextStory[0], STORY_PREFIX.matcher(bullet).replaceFirst(""));
						}
						doc.add(heading, bullet, edit.file.sections());
					}
				}
				case remove -> doc.removeBullet(heading, text);
				case removeSection -> doc.removeSection(heading);
			}
		}
		List<NarrativeDoc> written = new ArrayList<>();
		docs.forEach((file, doc) -> {
			String merged = doc.render(file.title());
			if (!doc.isEmpty() && !merged.equals(before.get(file))) {
				projects.writeNarrative(projectId, file, merged, note);
				written.add(file);
			}
		});
		return written;
	}

	private String syncInput(String projectId, String message, List<ChatTurn> history) {
		StringBuilder input = new StringBuilder("Current documents:\n").append(documents(projectId));
		if (!history.isEmpty()) {
			input.append("\n\nConversation so far:\n");
			history.forEach(t -> input.append(t.role()).append(": ").append(t.content()).append('\n'));
		}
		if (projects.entries(projectId, ProjectService.REQ_FILE).isEmpty()) {
			InterviewStep current = firstOpen(projects.completedSteps(projectId));
			if (current != null) {
				// without this the model files answers under whatever section it likes (a problem statement under Vision)
				input.append("\n\nCurrent interview step: ").append(current.label())
					.append(current.section() == null ? " (document " + current.doc().name() + ")"
							: " (section '" + current.section() + "')")
					.append(". The admin's latest answer is most likely about it: record it there. Use the Vision "
							+ "section only for what the product is.");
			}
		}
		return input.append("\nLatest message from the admin:\n").append(message).toString();
	}

	private String documents(String projectId) {
		StringBuilder out = new StringBuilder();
		for (NarrativeDoc doc : NarrativeDoc.values()) {
			String body = projects.narrativeBody(projectId, doc);
			out.append("=== ").append(doc.name()).append(" (").append(doc.path()).append(") ===\n")
				.append(body.isEmpty() ? "(not started)" : body)
				.append("\n\n");
		}
		return out.toString().strip();
	}

	private static String describeDocs() {
		StringBuilder out = new StringBuilder();
		for (NarrativeDoc doc : NarrativeDoc.values()) {
			out.append("- ").append(doc.name()).append(" (").append(doc.title()).append("): ").append(doc.guidance()).append('\n');
		}
		return out.toString().strip();
	}

}

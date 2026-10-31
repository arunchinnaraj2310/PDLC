package com.pdlc.assistant.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pdlc.assistant.dto.ChatTurn;
import com.pdlc.assistant.dto.DesignChangeDraft;
import com.pdlc.assistant.dto.DesignChangeDraft.Action;
import com.pdlc.assistant.dto.DesignChangeDraft.ComponentChange;
import com.pdlc.assistant.dto.DesignChangeDraft.LayerChoice;
import com.pdlc.assistant.dto.DesignChangeDraft.ReqChange;
import com.pdlc.assistant.dto.FilePatch;
import com.pdlc.assistant.dto.ImportProposal;
import com.pdlc.assistant.dto.LlmRequest;
import com.pdlc.assistant.dto.PatchResult;
import com.pdlc.assistant.model.MdDocument;
import com.pdlc.assistant.model.MdEntry;
import com.pdlc.assistant.model.PatchOp;

/**
 * A change request in chat ("add Redis caching") to a proposal. The model sees a sliced view of the model and
 * returns add/update/remove operations with explicit add/remove lists for links; everything else is
 * deterministic here: real ids are assigned, list edits are merged into the current entry, references left
 * dangling by a removal are cleaned up, and the result is validated as one patch set and dry-run rendered.
 * Nothing is written; the admin applies the proposal.
 */
@Service
public class DesignChangeService {

	private static final Logger log = LoggerFactory.getLogger(DesignChangeService.class);

	private static final int MAX_COMPLETION_TOKENS = 1200;

	private static final int CONTEXT_TURNS = 2;

	private static final int CONTEXT_CHARS = 300;

	private static final String SYSTEM = """
			You update an existing product design. The model lists requirements (REQ-###) and components \
			(CMP-###, each with a layer). The user asks for one change; return the smallest set of operations that \
			makes it.
			Reference existing entries by their exact ids. Give every NEW entry a short new key (n1, n2, ...) as \
			its id; you may use those keys in satisfies/links of other new or existing entries.
			add: fill every field. update: use an empty string for text, name or responsibility to keep the current \
			value, and layer "keep" to keep the layer. remove: only id and action matter. Every list is always \
			present (empty when there is nothing to add or remove).
			Links point from the caller to the callee: the CALLER lists the callee. addLinks/removeLinks change \
			only the links listed, never the others. A new component must be connected. Example: for "add Redis \
			caching" return a new cache component n1 (layer database, addLinks empty) AND an update of the existing \
			API component with addLinks [n1]; the cache never links back to the API. In the same way the UI or the \
			API links to a new login or identity provider, never the reverse. Databases, caches and external \
			services do not link to components in other layers.
			When the change refines a requirement that already exists (for example OAuth2 login refines "users sign \
			in"), update that requirement's text instead of adding an overlapping new one.
			Every new component must satisfy at least one requirement. If you add a new requirement, the component \
			that implements it must list that requirement's new key in addSatisfies; otherwise use an existing \
			requirement's id.
			Reuse existing components instead of adding duplicates. Component names are at most 3 words and 25 \
			characters (technologies are named after the technology). layer: messagebus is only for queues and \
			brokers; background jobs and workers are backend; an email or payment provider is external; a login \
			or identity provider is security. responsibility is one short sentence.
			If the message is a question, is too vague, or does not ask for a concrete change to the requirements \
			or architecture, return empty requirements and components lists and use summary to say what you \
			need to know.
			summary: one sentence saying what the change does.""";

	private final ProjectService projects;

	private final LlmGateway llm;

	private final ModelSlicer slicer;

	private final ProposalBuilder proposals;

	public DesignChangeService(ProjectService projects, LlmGateway llm, ModelSlicer slicer,
			ProposalBuilder proposals) {
		this.projects = projects;
		this.llm = llm;
		this.slicer = slicer;
		this.proposals = proposals;
	}

	public ImportProposal propose(String projectId, String message, List<ChatTurn> recent,
			Consumer<String> onStatus) {
		onStatus.accept("Looking at the current design…");
		List<MdEntry> reqs = projects.entries(projectId, ProjectService.REQ_FILE);
		List<MdEntry> cmps = projects.entries(projectId, ProjectService.CMP_FILE);
		StringBuilder input = new StringBuilder("Current model:\n").append(slicer.slice(reqs, cmps, message));
		if (!recent.isEmpty()) {
			input.append("\n\nRecent conversation (context only):\n");
			recent.subList(Math.max(0, recent.size() - CONTEXT_TURNS), recent.size())
				.forEach(t -> input.append(t.role()).append(": ").append(shorten(t.content())).append('\n'));
		}
		input.append("\n\nChange request:\n").append(message);

		onStatus.accept("Working out the design change…");
		DesignChangeDraft draft = llm.complete(
				new LlmRequest("design-change", SYSTEM, input.toString(), null, MAX_COMPLETION_TOKENS),
				DesignChangeDraft.class);

		onStatus.accept("Checking the change and rendering a preview of the diagram…");
		return toProposal(projectId, draft, reqs, cmps);
	}

	private ImportProposal toProposal(String projectId, DesignChangeDraft draft, List<MdEntry> reqs,
			List<MdEntry> cmps) {
		List<String> warnings = new ArrayList<>();
		Set<String> reqIds = ids(reqs);
		Set<String> cmpIds = ids(cmps);
		int nextReq = number(projects.nextId(projectId, ProjectService.REQ_FILE));
		int nextCmp = number(projects.nextId(projectId, ProjectService.CMP_FILE));
		Map<String, String> newReq = new LinkedHashMap<>();
		Map<String, String> newCmp = new LinkedHashMap<>();

		// pass 1: validate each change and give every new entry its real id, so pass 2 can resolve keys
		List<PatchOp> reqOps = new ArrayList<>();
		Set<String> removedReq = new HashSet<>();
		for (ReqChange r : orEmpty(draft.requirements)) {
			String id = clean(r.id);
			if (r.action == null || id.isEmpty()) {
				warnings.add("Ignored a requirement change without an action or id");
			}
			else if (r.action == Action.add) {
				if (value(r.text).isEmpty()) {
					warnings.add("Ignored a new requirement without text");
					continue;
				}
				String real = "REQ-%03d".formatted(nextReq++);
				newReq.put(key(id), real);
				reqOps.add(new PatchOp("add", real, value(r.text), Map.of()));
			}
			else if (!reqIds.contains(id.toUpperCase(Locale.ROOT))) {
				warnings.add("Ignored a change to unknown requirement " + id);
			}
			else if (r.action == Action.remove) {
				removedReq.add(id.toUpperCase(Locale.ROOT));
				reqOps.add(new PatchOp("remove", id.toUpperCase(Locale.ROOT), null, Map.of()));
			}
			else if (value(r.text).isEmpty()) {
				warnings.add("Ignored an update of " + id + " without new text");
			}
			else {
				reqOps.add(new PatchOp("update", id.toUpperCase(Locale.ROOT), value(r.text), Map.of()));
			}
		}

		List<ComponentChange> added = new ArrayList<>();
		Map<String, ComponentChange> updated = new LinkedHashMap<>();
		Set<String> removedCmp = new HashSet<>();
		for (ComponentChange c : orEmpty(draft.components)) {
			String id = clean(c.id);
			if (c.action == null || id.isEmpty()) {
				warnings.add("Ignored a component change without an action or id");
			}
			else if (c.action == Action.add) {
				if (value(c.name).isEmpty() || c.layer == null || c.layer == LayerChoice.keep) {
					warnings.add("Ignored a new component without a name or layer: " + id);
					continue;
				}
				newCmp.put(key(id), "CMP-%03d".formatted(nextCmp++));
				added.add(c);
			}
			else if (!cmpIds.contains(id.toUpperCase(Locale.ROOT))) {
				warnings.add("Ignored a change to unknown component " + id);
			}
			else if (c.action == Action.remove) {
				removedCmp.add(id.toUpperCase(Locale.ROOT));
			}
			else {
				updated.put(id.toUpperCase(Locale.ROOT), c);
			}
		}

		// pass 2: component operations. Existing entries first (remove / update, including cleaning references
		// to anything removed in this change), then the additions.
		List<PatchOp> cmpOps = new ArrayList<>();
		for (MdEntry e : cmps) {
			if (removedCmp.contains(e.id())) {
				cmpOps.add(new PatchOp("remove", e.id(), null, Map.of()));
				continue;
			}
			ComponentChange u = updated.get(e.id());
			List<String> satisfies = edit(e.list("satisfies"), u == null ? null : u.addSatisfies,
					u == null ? null : u.removeSatisfies, newReq, reqIds, removedReq, e.id(), warnings);
			List<String> links = edit(e.list("links"), u == null ? null : u.addLinks, u == null ? null : u.removeLinks,
					newCmp, cmpIds, removedCmp, e.id(), warnings);
			Map<String, String> attrs = new LinkedHashMap<>();
			if (!satisfies.equals(e.list("satisfies"))) {
				attrs.put("satisfies", String.join(", ", satisfies));
			}
			if (!links.equals(e.list("links"))) {
				attrs.put("links", String.join(", ", links));
			}
			String name = null;
			if (u != null) {
				if (u.layer != null && u.layer != LayerChoice.keep && !u.layer.name().equals(e.attrs().get("layer"))) {
					attrs.put("layer", u.layer.name());
				}
				if (!value(u.responsibility).isEmpty() && !value(u.responsibility).equals(e.attrs().get("responsibility"))) {
					attrs.put("responsibility", value(u.responsibility));
				}
				if (!value(u.name).isEmpty() && !value(u.name).equals(e.text())) {
					name = value(u.name);
				}
			}
			if (name != null || !attrs.isEmpty()) {
				cmpOps.add(new PatchOp("update", e.id(), name, attrs));
			}
		}
		for (ComponentChange a : added) {
			String real = newCmp.get(key(a.id));
			Map<String, String> attrs = new LinkedHashMap<>();
			attrs.put("layer", a.layer.name());
			attrs.put("responsibility", value(a.responsibility));
			attrs.put("satisfies", String.join(", ",
					edit(List.of(), a.addSatisfies, null, newReq, reqIds, removedReq, real, warnings)));
			attrs.put("links", String.join(", ",
					edit(List.of(), a.addLinks, null, newCmp, cmpIds, removedCmp, real, warnings)));
			cmpOps.add(new PatchOp("add", real, value(a.name), attrs));
		}

		List<FilePatch> patches = new ArrayList<>();
		if (!reqOps.isEmpty()) {
			patches.add(new FilePatch(ProjectService.REQ_FILE, reqOps));
		}
		if (!cmpOps.isEmpty()) {
			patches.add(new FilePatch(ProjectService.CMP_FILE, cmpOps));
		}
		if (patches.isEmpty()) {
			String why = clean(draft.summary);
			throw new IllegalArgumentException("I could not turn that into a design change"
					+ (why.isEmpty() ? "." : ": " + why) + " Say what to add, remove or change, for example "
					+ "\"add Redis caching\".");
		}
		Map<String, PatchResult> results = projects.previewAll(projectId, patches);
		warnAboutRemovals(reqOps, cmpOps, reqs, cmps, warnings);
		warnAboutNewEntries(results, cmps, newReq.values(), newCmp.values(), warnings);

		log.info("design-change project={} {} requirement ops, {} component ops, {} warnings", projectId,
				reqOps.size(), cmpOps.size(), warnings.size());
		String summary = clean(draft.summary).isEmpty() ? "I propose a design change." : clean(draft.summary);
		return proposals.build(projectId, summary, patches, warnings, results);
	}

	/** Current list plus additions minus removals minus anything removed elsewhere in this change; unknown refs warn. */
	private static List<String> edit(List<String> current, List<String> add, List<String> remove,
			Map<String, String> newIds, Set<String> existing, Set<String> removedNow, String owner,
			List<String> warnings) {
		Set<String> result = new LinkedHashSet<>(current);
		for (String ref : orEmpty(add)) {
			String id = resolve(ref, newIds, existing, owner, warnings);
			if (id != null) {
				result.add(id);
			}
		}
		for (String ref : orEmpty(remove)) {
			String id = resolve(ref, newIds, existing, owner, warnings);
			if (id != null) {
				result.remove(id);
			}
		}
		result.removeAll(removedNow);
		result.remove(owner);
		return new ArrayList<>(result);
	}

	private static String resolve(String ref, Map<String, String> newIds, Set<String> existing, String owner,
			List<String> warnings) {
		String k = key(ref);
		if (newIds.containsKey(k)) {
			return newIds.get(k);
		}
		if (existing.contains(clean(ref).toUpperCase(Locale.ROOT))) {
			return clean(ref).toUpperCase(Locale.ROOT);
		}
		if (!k.isEmpty()) {
			warnings.add(owner + " referenced an unknown entry '" + clean(ref) + "'; dropped");
		}
		return null;
	}

	/** Removals are the one destructive kind of change, so call them out even though Apply is still required. */
	private static void warnAboutRemovals(List<PatchOp> reqOps, List<PatchOp> cmpOps, List<MdEntry> reqs,
			List<MdEntry> cmps, List<String> warnings) {
		for (PatchOp op : reqOps) {
			if (op.op().equals("remove")) {
				warnings.add("This change removes " + op.id() + " (" + nameOf(reqs, op.id()) + ")");
			}
		}
		for (PatchOp op : cmpOps) {
			if (op.op().equals("remove")) {
				warnings.add("This change removes " + op.id() + " (" + nameOf(cmps, op.id()) + ")");
			}
		}
	}

	private static String nameOf(List<MdEntry> entries, String id) {
		return entries.stream().filter(e -> e.id().equals(id)).map(MdEntry::text).findFirst().orElse("?");
	}

	/** New components should be connected and new requirements implemented; flag them if not. */
	private static void warnAboutNewEntries(Map<String, PatchResult> results, List<MdEntry> before,
			Iterable<String> newReqIds, Iterable<String> newCmpIds, List<String> warnings) {
		PatchResult changed = results.get(ProjectService.CMP_FILE);
		List<MdEntry> after = changed == null ? before : MdDocument.parse(changed.content()).entries();
		for (String id : newCmpIds) {
			boolean connected = after.stream()
				.anyMatch(c -> (c.id().equals(id) && !c.list("links").isEmpty()) || c.list("links").contains(id));
			if (!connected) {
				String name = after.stream().filter(c -> c.id().equals(id)).map(MdEntry::text).findFirst().orElse("?");
				warnings.add(id + " (" + name + ") is not linked to any other component");
			}
		}
		for (String id : newReqIds) {
			if (after.stream().noneMatch(c -> c.list("satisfies").contains(id))) {
				warnings.add(id + " is not satisfied by any component (unimplemented)");
			}
		}
	}

	private static Set<String> ids(List<MdEntry> entries) {
		Set<String> ids = new HashSet<>();
		entries.forEach(e -> ids.add(e.id()));
		return ids;
	}

	private static String clean(String s) {
		return s == null ? "" : s.strip();
	}

	/** Text field of a change: blank, or the model writing the word "keep", both mean "leave unchanged". */
	private static String value(String s) {
		String v = clean(s);
		return v.equalsIgnoreCase("keep") ? "" : v;
	}

	private static String key(String s) {
		return clean(s).toLowerCase(Locale.ROOT);
	}

	private static String shorten(String text) {
		return text.length() > CONTEXT_CHARS ? text.substring(0, CONTEXT_CHARS) + "…" : text;
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}

	private static int number(String id) {
		return Integer.parseInt(id.substring(id.indexOf('-') + 1));
	}

}

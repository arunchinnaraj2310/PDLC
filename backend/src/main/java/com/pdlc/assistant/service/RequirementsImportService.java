package com.pdlc.assistant.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pdlc.assistant.dto.FilePatch;
import com.pdlc.assistant.dto.ImportProposal;
import com.pdlc.assistant.dto.LlmRequest;
import com.pdlc.assistant.dto.PatchResult;
import com.pdlc.assistant.dto.RequirementsDraft;
import com.pdlc.assistant.model.PatchOp;

/**
 * requirements.md to design: split locally, ask the LLM for requirements + components in one schema-enforced
 * call, then turn the answer into add-only patches with backend-assigned ids. Nothing is written here.
 */
@Service
public class RequirementsImportService {

	private static final Logger log = LoggerFactory.getLogger(RequirementsImportService.class);

	private static final int MAX_COMPLETION_TOKENS = 2000;

	private static final String SYSTEM = """
			You turn a product requirements document into (1) atomic requirements and (2) the minimal set of \
			architecture components needed to build it.
			Requirements: one per distinct capability or constraint, each a short sentence. Do not turn technology \
			choices into separate requirements. Use keys r1, r2, ... in order.
			Components: use keys c1, c2, ... in order. name is at most 3 words and 25 characters (technologies \
			such as PostgreSQL or Redis are components, named after the technology). layer must be one of the \
			allowed values (messagebus is only for queues and brokers; background jobs and workers are backend; \
			an email or payment provider is external). responsibility is one short sentence.
			satisfies lists the keys of the requirements the component directly implements. Every requirement must \
			be satisfied by at least one component: if a requirement needs a capability no other component \
			provides (for example caching needs a cache component), add that component.
			links lists the keys of the components it calls or depends on, caller to callee. The diagram must be \
			connected: the UI links to the API, the API links to every database, cache or service it uses, and a \
			background job links to everything it reads, writes or calls. Only databases and external services may \
			have empty links.
			Use only keys you defined. Do not invent requirements that the document does not support.""";

	private final ProjectService projects;

	private final LlmGateway llm;

	private final RequirementsSplitter splitter;

	private final ProposalBuilder proposals;

	public RequirementsImportService(ProjectService projects, LlmGateway llm, RequirementsSplitter splitter,
			ProposalBuilder proposals) {
		this.projects = projects;
		this.llm = llm;
		this.splitter = splitter;
		this.proposals = proposals;
	}

	public ImportProposal propose(String projectId, String markdown) {
		return propose(projectId, markdown, status -> {
		});
	}

	/** {@code onStatus} is told each stage as it starts (used to stream progress to the chat). */
	public ImportProposal propose(String projectId, String markdown, Consumer<String> onStatus) {
		onStatus.accept("Reading the requirements…");
		String input = splitter.compact(markdown);
		int nextReq = number(projects.nextId(projectId, ProjectService.REQ_FILE));
		int nextCmp = number(projects.nextId(projectId, ProjectService.CMP_FILE));

		onStatus.accept("Drafting requirements and building blocks…");
		RequirementsDraft draft = llm.complete(
				new LlmRequest("import-requirements", SYSTEM, input, null, MAX_COMPLETION_TOKENS),
				RequirementsDraft.class);

		onStatus.accept("Checking references and rendering a preview of the diagram…");
		List<String> warnings = new ArrayList<>();
		Map<String, String> reqIds = new LinkedHashMap<>();
		List<PatchOp> reqOps = new ArrayList<>();
		for (RequirementsDraft.Req r : orEmpty(draft.requirements)) {
			if (r.key == null || r.text == null || r.text.isBlank() || reqIds.containsKey(r.key)) {
				warnings.add("Skipped a requirement with a missing text or a duplicate key: " + r.key);
				continue;
			}
			String id = "REQ-%03d".formatted(nextReq++);
			reqIds.put(r.key, id);
			reqOps.add(new PatchOp("add", id, r.text, Map.of()));
		}
		if (reqOps.isEmpty()) {
			throw new IllegalArgumentException("No requirements could be extracted from the document");
		}

		Map<String, String> cmpIds = new LinkedHashMap<>();
		for (RequirementsDraft.Component c : orEmpty(draft.components)) {
			if (c.key != null && c.name != null && !c.name.isBlank() && !cmpIds.containsKey(c.key)) {
				cmpIds.put(c.key, "CMP-%03d".formatted(nextCmp++));
			}
		}
		Set<String> satisfied = new LinkedHashSet<>();
		List<PatchOp> cmpOps = new ArrayList<>();
		for (RequirementsDraft.Component c : orEmpty(draft.components)) {
			String id = cmpIds.get(c.key);
			if (id == null) {
				continue;
			}
			Map<String, String> attrs = new LinkedHashMap<>();
			attrs.put("layer", c.layer.name());
			attrs.put("responsibility", c.responsibility);
			attrs.put("satisfies", resolve(c.satisfies, reqIds, c.name, "requirement", warnings, satisfied));
			attrs.put("links", resolve(c.links, cmpIds, c.name, "component", warnings, null));
			cmpOps.add(new PatchOp("add", id, c.name, attrs));
		}
		Set<String> connected = new LinkedHashSet<>();
		for (PatchOp op : cmpOps) {
			String links = op.attrs().get("links");
			if (links != null && !links.isBlank()) {
				connected.add(op.id());
				connected.addAll(List.of(links.split(", ")));
			}
		}
		for (PatchOp op : cmpOps) {
			if (cmpOps.size() > 1 && !connected.contains(op.id())) {
				warnings.add(op.id() + " (" + op.text() + ") has no links to or from other components");
			}
		}
		reqIds.forEach((key, id) -> {
			if (!satisfied.contains(id)) {
				warnings.add(id + " is not satisfied by any component (unimplemented)");
			}
		});

		List<FilePatch> patches = List.of(new FilePatch(ProjectService.REQ_FILE, reqOps),
				new FilePatch(ProjectService.CMP_FILE, cmpOps));
		Map<String, PatchResult> results = projects.previewAll(projectId, patches);
		log.info("import-requirements project={} proposed {} requirements, {} components, {} warnings", projectId,
				reqOps.size(), cmpOps.size(), warnings.size());
		String summary = "I propose %d requirements and %d components.".formatted(reqOps.size(), cmpOps.size());
		return proposals.build(projectId, summary, patches, warnings, results);
	}

	/** Maps the model's keys to real ids; unknown keys are dropped with a warning. */
	private static String resolve(List<String> keys, Map<String, String> ids, String owner, String kind,
			List<String> warnings, Set<String> used) {
		List<String> resolved = new ArrayList<>();
		for (String key : orEmpty(keys)) {
			String id = ids.get(key);
			if (id == null) {
				warnings.add("Component '" + owner + "' referenced an unknown " + kind + " '" + key + "'; dropped");
			}
			else if (!resolved.contains(id)) {
				resolved.add(id);
				if (used != null) {
					used.add(id);
				}
			}
		}
		return String.join(", ", resolved);
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}

	private static int number(String id) {
		return Integer.parseInt(id.substring(id.indexOf('-') + 1));
	}

}

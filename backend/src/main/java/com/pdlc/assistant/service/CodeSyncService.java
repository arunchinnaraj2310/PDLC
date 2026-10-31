package com.pdlc.assistant.service;

import java.nio.file.Files;
import java.nio.file.Path;
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

import com.pdlc.assistant.dto.FilePatch;
import com.pdlc.assistant.dto.ImportProposal;
import com.pdlc.assistant.dto.PatchResult;
import com.pdlc.assistant.model.CodeFacts;
import com.pdlc.assistant.model.CodeFile;
import com.pdlc.assistant.model.MdDocument;
import com.pdlc.assistant.model.MdEntry;
import com.pdlc.assistant.model.PatchOp;
import com.pdlc.assistant.service.CodeGraphService.Cluster;
import com.pdlc.assistant.service.CodeGraphService.Graph;
import com.pdlc.assistant.service.CodeInventoryService.External;
import com.pdlc.assistant.service.CodeInventoryService.Inventory;
import com.pdlc.assistant.service.CodeLabelService.Label;

import tools.jackson.databind.json.JsonMapper;

/**
 * Step 7 end to end: code to design. Inventory and extraction are deterministic and incremental (only files
 * whose hash changed are parsed again); the LLM only names clusters it has not seen. The result is reconciled
 * with {@code 11-building-blocks.md} into an add/update/remove patch, plus drift warnings, and handed back as a
 * proposal for Apply/Reject. Nothing is written to the md files here.
 */
@Service
public class CodeSyncService {

	private static final Logger log = LoggerFactory.getLogger(CodeSyncService.class);

	private static final String FACTS = ".pdlc/facts.json";

	/** A component the code implies, matched to an existing CMP or about to become a new one. */
	private static final class Comp {

		final String code;

		final boolean external;

		MdEntry existing;

		String id;

		String name;

		String layer;

		String responsibility;

		String language = "";

		String technology = "";

		List<String> satisfies = List.of();

		String evidence = "";

		final Set<String> linkCodes = new LinkedHashSet<>();

		Comp(String code, boolean external) {
			this.code = code;
			this.external = external;
		}

	}

	private final ProjectService projects;

	private final CodeInventoryService inventory;

	private final CodeExtractorRunner extractor;

	private final CodeGraphService graphs;

	private final CodeLabelService labels;

	private final ProposalBuilder proposals;

	private final JsonMapper json;

	private final GenerationRecord generated;

	public CodeSyncService(ProjectService projects, CodeInventoryService inventory, CodeExtractorRunner extractor,
			CodeGraphService graphs, CodeLabelService labels, ProposalBuilder proposals, JsonMapper json,
			GenerationRecord generated) {
		this.generated = generated;
		this.projects = projects;
		this.inventory = inventory;
		this.extractor = extractor;
		this.graphs = graphs;
		this.labels = labels;
		this.proposals = proposals;
		this.json = json;
	}

	/**
	 * @param sourceArg a folder to read, or null for the folder used last time, or the project's own
	 * {@code implementation/} folder
	 */
	public ImportProposal sync(String projectId, String sourceArg, Consumer<String> status) {
		CodeFacts cached = loadFacts(projectId);
		Path root = resolveSource(projectId, sourceArg, cached);

		status.accept("Scanning " + root + "…");
		Inventory inv = inventory.scan(root);
		Map<String, CodeFile> previous = cached.source() != null && cached.source().equals(root.toString())
				? cached.files() : Map.of();
		List<String> changed = inv.sourceHashes().entrySet()
			.stream()
			.filter(e -> previous.get(e.getKey()) == null || !e.getValue().equals(previous.get(e.getKey()).hash()))
			.map(Map.Entry::getKey)
			.toList();

		List<String> warnings = new ArrayList<>();
		Map<String, CodeFile> files = new LinkedHashMap<>();
		CodeExtractorRunner.Extraction extraction = new CodeExtractorRunner.Extraction(Map.of(), List.of());
		if (!changed.isEmpty()) {
			status.accept("Reading " + changed.size() + " of " + inv.sourceHashes().size() + " files (the rest are unchanged)…");
			extraction = extractor.extract(root, changed);
		}
		for (Map.Entry<String, String> e : inv.sourceHashes().entrySet()) {
			CodeFile fresh = extraction.files().get(e.getKey());
			CodeFile file = fresh != null ? fresh.with(e.getKey(), e.getValue())
					: changed.contains(e.getKey()) ? null : previous.get(e.getKey());
			if (file != null) {
				files.put(e.getKey(), file);
			}
		}
		saveFacts(projectId, new CodeFacts(root.toString(), files));
		if (!extraction.errors().isEmpty()) {
			warnings.add(extraction.errors().size() + " files could not be read and were left out, e.g. "
					+ extraction.errors().get(0));
		}
		long partial = files.values().stream().filter(CodeFile::partial).count();
		if (partial > 0) {
			warnings.add(partial + " files have syntax the parser could not fully read; their facts may be incomplete");
		}
		if (inv.skipped() > 0) {
			warnings.add(inv.skipped() + " source files over 1 MB were skipped");
		}
		if (files.isEmpty()) {
			throw new IllegalArgumentException("None of the source files could be read");
		}

		status.accept("Grouping files into components…");
		// files written by code generation belong to the component they were generated for
		boolean generatedTree = projects.implementationDir(projectId)
			.map(d -> d.toAbsolutePath().normalize().equals(root))
			.orElse(false);
		Graph graph = graphs.build(files, inv.externals(), generatedTree ? generated.componentMap(projectId) : Map.of());
		warnings.addAll(graph.notes());

		List<MdEntry> existing = projects.entries(projectId, ProjectService.CMP_FILE);
		List<MdEntry> reqs = projects.entries(projectId, ProjectService.REQ_FILE);
		List<Comp> comps = components(projectId, graph, inv, files, existing, reqs, status);
		return reconcile(projectId, root, files.size(), changed.size(), comps, existing, reqs, warnings);
	}

	/** What the last sync did, for the UI to notice (version changes when the design changed) and for the watcher. */
	public record AutoSync(int version, String at, String summary, List<String> warnings, String signature,
			String source, String error) {

		public AutoSync {
			warnings = warnings == null ? List.of() : warnings;
		}

	}

	private static final String AUTO = ".pdlc/autosync.json";

	public AutoSync autoStatus(String projectId) {
		String raw = projects.readIfExists(projectId, AUTO);
		if (raw != null) {
			try {
				return json.readValue(raw, AutoSync.class);
			}
			catch (RuntimeException e) {
				log.warn("Ignoring unreadable autosync status of {}: {}", projectId, e.getMessage());
			}
		}
		return new AutoSync(0, null, null, List.of(), null, null, null);
	}

	/** The folder the last sync read, or null when the project was never synced. */
	public String knownSource(String projectId) {
		return loadFacts(projectId).source();
	}

	/** Remembers the current fingerprint of a source folder without syncing (nothing is known to have changed). */
	public void recordBaseline(String projectId, String source, String signature) {
		AutoSync last = autoStatus(projectId);
		projects.writeInternal(projectId, AUTO, json.writeValueAsString(new AutoSync(last.version(), last.at(),
				last.summary(), last.warnings(), signature, source, last.error())));
	}

	/** Records a failed background sync so it shows in the status instead of only in the log. */
	public void recordError(String projectId, String error) {
		AutoSync last = autoStatus(projectId);
		projects.writeInternal(projectId, AUTO, json.writeValueAsString(new AutoSync(last.version(), last.at(),
				last.summary(), last.warnings(), last.signature(), last.source(), error)));
	}

	/**
	 * Like {@link #sync} but applies the proposal at once: code is the truth here, and the md backup, changelog and
	 * Undo are still there. Used after code generation and by the background watcher. Returns a markdown report.
	 */
	public String syncAndApply(String projectId, String sourceArg, Consumer<String> status) {
		Path root = resolveSource(projectId, sourceArg, loadFacts(projectId));
		// fingerprint first: a file saved while the sync runs then shows up as a change on the next poll
		String signature = inventory.signature(root);
		ImportProposal proposal = sync(projectId, root.toString(), status);
		AutoSync last = autoStatus(projectId);
		int version = last.version();
		String text = proposal.summary();
		if (!proposal.patches().isEmpty()) {
			projects.applyAll(projectId, "code sync: " + proposal.summary(), proposal.patches());
			version++;
			text = "Design updated from the code. " + proposal.summary();
		}
		projects.writeInternal(projectId, AUTO, json.writeValueAsString(new AutoSync(version,
				java.time.Instant.now().toString(), text, proposal.warnings(), signature, root.toString(), null)));
		StringBuilder report = new StringBuilder(text);
		if (!proposal.warnings().isEmpty()) {
			report.append("\n\nDrift report:\n");
			proposal.warnings().forEach(w -> report.append("- ").append(w).append('\n'));
		}
		return report.toString();
	}

	// ---------- components ----------

	private List<Comp> components(String projectId, Graph graph, Inventory inv, Map<String, CodeFile> files,
			List<MdEntry> existing, List<MdEntry> reqs, Consumer<String> status) {
		Map<String, MdEntry> byCode = new LinkedHashMap<>();
		List<MdEntry> designed = new ArrayList<>();
		for (MdEntry e : existing) {
			String code = e.attrs().get("code");
			if (code == null || code.isBlank()) {
				designed.add(e);
			}
			else {
				byCode.put(code.strip(), e);
			}
		}

		Map<String, MdEntry> byId = new LinkedHashMap<>();
		existing.forEach(e -> byId.put(e.id(), e));
		// a generated cluster "cmp:CMP-003" is that design component already: nothing to name or match
		List<Cluster> unlabelled = graph.clusters()
			.stream()
			.filter(c -> !byCode.containsKey(c.key())
					&& !(c.key().startsWith("cmp:") && byId.containsKey(c.key().substring(4))))
			.toList();
		Map<String, Label> labelled = Map.of();
		if (!unlabelled.isEmpty()) {
			status.accept("Naming " + unlabelled.size() + " components…");
			labelled = labels.label(projectId, unlabelled, graph, files, reqs);
		}

		List<Comp> comps = new ArrayList<>();
		Set<MdEntry> adopted = new HashSet<>();
		for (Cluster cluster : graph.clusters()) {
			Comp comp = new Comp(cluster.key(), false);
			comp.existing = byCode.get(cluster.key());
			if (comp.existing == null && cluster.key().startsWith("cmp:")) {
				comp.existing = byId.get(cluster.key().substring(4));
			}
			Label label = labelled.get(cluster.key());
			if (comp.existing == null && label != null) {
				comp.existing = designed.stream().filter(d -> !adopted.contains(d) && sameName(d.text(), label.name())).findFirst().orElse(null);
				if (comp.existing != null) {
					adopted.add(comp.existing);
				}
			}
			comp.name = comp.existing != null ? comp.existing.text() : label.name();
			comp.layer = label != null ? label.layer() : cluster.layer();
			comp.responsibility = label != null ? label.responsibility() : "";
			comp.satisfies = label != null ? label.satisfies() : List.of();
			comp.evidence = evidence(cluster, files);
			comp.language = languageOf(cluster, files);
			comp.technology = technologyOf(cluster, files);
			comps.add(comp);
		}
		for (External external : inv.externals()) {
			Comp comp = new Comp("ext:" + external.name(), true);
			comp.existing = byCode.get(comp.code);
			if (comp.existing == null) {
				comp.existing = designed.stream().filter(d -> !adopted.contains(d) && sameName(d.text(), external.name())).findFirst().orElse(null);
				if (comp.existing != null) {
					adopted.add(comp.existing);
				}
			}
			comp.name = comp.existing != null ? comp.existing.text() : external.name();
			comp.layer = external.layer();
			comp.responsibility = external.responsibility();
			comp.evidence = external.evidence();
			comp.technology = external.name();
			comps.add(comp);
		}

		// links, caller to callee: imports between clusters, and clusters to the external systems they use
		graph.edges().forEach(e -> find(comps, e.from()).linkCodes.add(e.to()));
		String entry = entryCluster(graph, files);
		for (Comp comp : comps) {
			if (!comp.external) {
				continue;
			}
			Set<String> users = graph.externalUsers().getOrDefault(comp.code.substring(4), Set.of());
			if (users.isEmpty() && entry != null) {
				users = Set.of(entry);
			}
			users.forEach(u -> find(comps, u).linkCodes.add(comp.code));
		}
		return comps;
	}

	private ImportProposal reconcile(String projectId, Path root, int fileCount, int changedCount, List<Comp> comps,
			List<MdEntry> existing, List<MdEntry> reqs, List<String> warnings) {
		int next = number(projects.nextId(projectId, ProjectService.CMP_FILE));
		for (Comp comp : comps) {
			comp.id = comp.existing != null ? comp.existing.id() : "CMP-%03d".formatted(next++);
		}
		Map<String, Comp> byCode = new LinkedHashMap<>();
		comps.forEach(c -> byCode.put(c.code, c));

		// components the code once implied that it no longer does
		List<MdEntry> stale = existing.stream()
			.filter(e -> e.attrs().get("code") != null && !e.attrs().get("code").isBlank()
					&& !byCode.containsKey(e.attrs().get("code").strip()))
			.toList();
		Set<String> staleIds = new HashSet<>();
		stale.forEach(e -> staleIds.add(e.id()));
		Set<String> matched = new HashSet<>();
		comps.stream().filter(c -> c.existing != null).forEach(c -> matched.add(c.id));

		List<PatchOp> ops = new ArrayList<>();
		int added = 0;
		int updated = 0;
		for (Comp comp : comps) {
			List<String> derived = comp.linkCodes.stream().map(code -> byCode.get(code).id).toList();
			if (comp.existing == null) {
				Map<String, String> attrs = new LinkedHashMap<>();
				attrs.put("layer", comp.layer);
				put(attrs, "responsibility", comp.responsibility);
				put(attrs, "language", comp.language);
				put(attrs, "technology", comp.technology);
				put(attrs, "satisfies", String.join(", ", comp.satisfies));
				put(attrs, "links", String.join(", ", derived));
				attrs.put("code", comp.code);
				put(attrs, "evidence", comp.evidence);
				ops.add(new PatchOp("add", comp.id, comp.name, attrs));
				added++;
				if (!comp.external && !reqs.isEmpty() && comp.satisfies.isEmpty()) {
					warnings.add(comp.id + " (" + comp.name + ") is in the code but satisfies no requirement (drift)");
				}
				continue;
			}
			Map<String, String> change = new LinkedHashMap<>();
			String code = comp.existing.attrs().get("code");
			if ((code == null || code.isBlank()) && !comp.code.startsWith("cmp:")) {
				change.put("code", comp.code);
				put(change, "evidence", comp.evidence);
			}
			// what the code shows fills in what the design leaves blank, never overwrites what it says
			if (blank(comp.existing.attrs().get("language"))) {
				put(change, "language", comp.language);
			}
			if (blank(comp.existing.attrs().get("technology"))) {
				put(change, "technology", comp.technology);
			}
			// links the code shows are added; links already in the design are never dropped (only ones to removed
			// components), because a designed link the code does not show yet may simply be unbuilt
			List<String> links = new ArrayList<>(comp.existing.list("links").stream().filter(l -> !staleIds.contains(l)).toList());
			derived.stream().filter(l -> !links.contains(l) && !l.equals(comp.id)).forEach(links::add);
			if (!links.equals(comp.existing.list("links"))) {
				change.put("links", String.join(", ", links));
			}
			if (!change.isEmpty()) {
				ops.add(new PatchOp("update", comp.id, null, change));
				updated++;
			}
		}
		for (MdEntry e : stale) {
			ops.add(new PatchOp("remove", e.id(), null, Map.of()));
		}
		// designed components that are not code-managed may still point at a component that is being removed
		for (MdEntry e : existing) {
			boolean touched = matched.contains(e.id()) || staleIds.contains(e.id());
			if (!touched) {
				List<String> links = e.list("links").stream().filter(l -> !staleIds.contains(l)).toList();
				if (links.size() != e.list("links").size()) {
					ops.add(new PatchOp("update", e.id(), null, Map.of("links", String.join(", ", links))));
				}
				if (e.attrs().get("code") == null || e.attrs().get("code").isBlank()) {
					warnings.add(e.id() + " (" + e.text() + ") is in the design but no code or dependency matches it");
				}
			}
		}

		log.info("code-sync project={} files={} changed={} comps={} +{} ~{} -{}", projectId, fileCount, changedCount,
				comps.size(), added, updated, stale.size());
		String found = "Found %d components in %d files (%d read this time).".formatted(comps.size(), fileCount, changedCount);
		if (ops.isEmpty()) {
			return new ImportProposal(found + " The design already matches the code.", List.of(), List.of(), warnings);
		}
		List<FilePatch> patches = List.of(new FilePatch(ProjectService.CMP_FILE, ops));
		Map<String, PatchResult> results = projects.previewAll(projectId, patches);

		Set<String> satisfied = new HashSet<>();
		MdDocument.parse(results.get(ProjectService.CMP_FILE).content()).entries().forEach(e -> satisfied.addAll(e.list("satisfies")));
		reqs.stream()
			.filter(r -> !satisfied.contains(r.id()))
			.forEach(r -> warnings.add(r.id() + " is not satisfied by any component (unimplemented)"));

		String summary = "%s I propose %d new, %d changed and %d removed components.".formatted(found, added, updated, stale.size());
		return proposals.build(projectId, summary, patches, warnings, results);
	}

	// ---------- helpers ----------

	private Path resolveSource(String projectId, String sourceArg, CodeFacts cached) {
		if (sourceArg != null && !sourceArg.isBlank()) {
			Path path = Path.of(sourceArg.strip().replaceAll("^[\"']|[\"']$", "")).toAbsolutePath().normalize();
			if (!Files.isDirectory(path)) {
				throw new IllegalArgumentException("That folder does not exist: " + path);
			}
			return path;
		}
		if (cached.source() != null && Files.isDirectory(Path.of(cached.source()))) {
			return Path.of(cached.source());
		}
		return projects.implementationDir(projectId)
			.orElseThrow(() -> new IllegalArgumentException(
					"Tell me which folder to read, for example: sync code from C:\\projects\\my-app"));
	}

	private CodeFacts loadFacts(String projectId) {
		String raw = projects.readIfExists(projectId, FACTS);
		if (raw == null) {
			return new CodeFacts(null, Map.of());
		}
		try {
			return json.readValue(raw, CodeFacts.class);
		}
		catch (RuntimeException e) {
			log.warn("Ignoring unreadable facts cache of {}: {}", projectId, e.getMessage());
			return new CodeFacts(null, Map.of());
		}
	}

	private void saveFacts(String projectId, CodeFacts facts) {
		projects.writeInternal(projectId, FACTS, json.writeValueAsString(facts));
	}

	private static Comp find(List<Comp> comps, String code) {
		return comps.stream().filter(c -> c.code.equals(code)).findFirst().orElseThrow();
	}

	/**
	 * Where external systems that no file imports attach: the cluster holding the application entry point, or else
	 * the largest backend cluster (a skeleton has no main method, and "first alphabetically" is arbitrary).
	 */
	private static String entryCluster(Graph graph, Map<String, CodeFile> files) {
		return files.values()
			.stream()
			.filter(CodeFile::hasMain)
			.map(f -> graph.fileCluster().get(f.path()))
			.findFirst()
			.orElse(graph.clusters()
				.stream()
				.filter(c -> c.layer().equals("backend"))
				.max(java.util.Comparator.comparingInt((Cluster c) -> c.files().size()).thenComparing(Cluster::key))
				.map(Cluster::key)
				.orElse(null));
	}

	/** file:line of the most telling fact in a cluster: a route, else a type, else the first file. */
	private static String evidence(Cluster cluster, Map<String, CodeFile> files) {
		for (String path : cluster.files()) {
			CodeFile f = files.get(path);
			if (!f.routes().isEmpty()) {
				return path + ":" + f.routes().get(0).line();
			}
		}
		for (String path : cluster.files()) {
			CodeFile f = files.get(path);
			if (!f.types().isEmpty()) {
				return path + ":" + f.types().get(0).line();
			}
		}
		return cluster.files().get(0) + ":1";
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	/** The language most of the cluster's files are written in. */
	private static String languageOf(Cluster cluster, Map<String, CodeFile> files) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String path : cluster.files()) {
			String lang = switch (files.get(path).lang()) {
				case "java" -> "Java";
				case "ts", "tsx" -> "TypeScript";
				default -> "JavaScript";
			};
			counts.merge(lang, 1, Integer::sum);
		}
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
	}

	/** Framework evidenced by the imports of the cluster's files (Spring Boot, React, Express, ...). */
	private static String technologyOf(Cluster cluster, Map<String, CodeFile> files) {
		List<String> specs = cluster.files()
			.stream()
			.flatMap(p -> files.get(p).imports().stream())
			.map(CodeFile.Import::spec)
			.toList();
		String[][] known = { { "org.springframework.boot", "Spring Boot" }, { "org.springframework", "Spring" },
				{ "jakarta.servlet", "Servlet" }, { "react", "React" }, { "vue", "Vue" }, { "@angular/", "Angular" },
				{ "svelte", "Svelte" }, { "express", "Express" }, { "@nestjs/", "NestJS" }, { "fastify", "Fastify" } };
		for (String[] k : known) {
			boolean hit = specs.stream()
				.anyMatch(s -> s.equals(k[0]) || s.startsWith(k[0] + ".") || s.startsWith(k[0] + "/")
						|| (k[0].endsWith("/") && s.startsWith(k[0])));
			if (hit) {
				return k[1];
			}
		}
		return "";
	}

	private static void put(Map<String, String> attrs, String key, String value) {
		if (value != null && !value.isBlank()) {
			attrs.put(key, value);
		}
	}

	/**
	 * "Redis" and "Redis Cache" are the same thing; so are "PostgreSQL" and "postgresql", and "JWT Auth" and "JWT
	 * Authentication". Every word of the shorter name must start the same as a word of the longer one.
	 */
	private static boolean sameName(String a, String b) {
		List<String> x = words(a);
		List<String> y = words(b);
		if (x.isEmpty() || y.isEmpty()) {
			return false;
		}
		List<String> shorter = x.size() <= y.size() ? x : y;
		List<String> longer = shorter == x ? y : x;
		return shorter.stream().allMatch(w -> longer.stream().anyMatch(l -> l.startsWith(w) || w.startsWith(l)));
	}

	private static List<String> words(String name) {
		return java.util.Arrays.stream(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").split(" +"))
			.filter(w -> w.length() > 1)
			.toList();
	}

	private static int number(String id) {
		return Integer.parseInt(id.substring(id.indexOf('-') + 1));
	}

}

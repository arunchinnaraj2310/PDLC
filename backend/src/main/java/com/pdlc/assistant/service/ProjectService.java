package com.pdlc.assistant.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.pdlc.assistant.dto.FilePatch;
import com.pdlc.assistant.dto.PatchResult;
import com.pdlc.assistant.model.InterviewStep;
import com.pdlc.assistant.model.Layer;
import com.pdlc.assistant.model.MdDocument;
import com.pdlc.assistant.model.MdEntry;
import com.pdlc.assistant.model.NarrativeDoc;
import com.pdlc.assistant.model.PatchOp;
import com.pdlc.assistant.repository.ProjectStore;

/**
 * Creates projects and applies section-level patches to their md files.
 * Every applied patch: validates, backs up the previous file, bumps the version,
 * writes the file and appends a line to changelog.md.
 */
@Service
public class ProjectService {

	public static final String REQ_FILE = "docs/04-technical-requirements.md";

	public static final String CMP_FILE = "docs/11-building-blocks.md";

	static final String CHANGELOG = "changelog.md";

	private static final String HISTORY = ".pdlc/history/";

	private static final Map<String, String> ID_PREFIX = Map.of(REQ_FILE, "REQ", CMP_FILE, "CMP");

	/** Archify component types; the diagram mapper maps CMP layer straight onto these. */
	static final Set<String> LAYERS = Arrays.stream(Layer.values()).map(Layer::name).collect(Collectors.toSet());

	private static final Pattern PROJECT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,40}");

	private static final Pattern NARRATIVE_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

	private static final Pattern NARRATIVE_DONE = Pattern.compile("(?m)^done:[ \\t]*(.*)$");

	private static final Pattern NARRATIVE_DONE_LINE = Pattern.compile("(?m)^done:.*\\r?\\n?");

	private static final Pattern NARRATIVE_VERSION =Pattern.compile("(?m)^version:\\s*(\\d+)");

	private final ProjectStore store;

	public ProjectService(ProjectStore store) {
		this.store = store;
	}

	public List<String> listProjects() {
		return store.listProjects();
	}

	public void create(String projectId) {
		checkProjectId(projectId);
		if (!store.list(projectId, "").isEmpty()) {
			throw new IllegalArgumentException("Project already exists: " + projectId);
		}
		store.write(projectId, REQ_FILE, """
				---
				id: DOC-04
				version: 1
				status: draft
				---
				# Technical Requirements

				<!-- One requirement per entry: "- REQ-001: text". -->
				""");
		store.write(projectId, CMP_FILE, """
				---
				id: DOC-11
				version: 1
				status: draft
				---
				# Building Blocks

				<!-- One component per entry: "- CMP-001: Name", then indented "- key: value" lines:
				     layer (frontend|backend|database|cloud|security|messagebus|external, required),
				     responsibility, satisfies (REQ ids, comma-separated), links (CMP ids, comma-separated). -->
				""");
		for (NarrativeDoc doc : NarrativeDoc.values()) {
			store.write(projectId, doc.path(), doc.template());
		}
		store.write(projectId, CHANGELOG, "# Changelog\n\n");
		log(projectId, "project created");
	}

	public String readFile(String projectId, String path) {
		requireProject(projectId);
		if (!store.exists(projectId, path)) {
			// projects created before the interview existed have no narrative files yet
			return NarrativeDoc.byPath(path)
				.map(NarrativeDoc::template)
				.orElseThrow(() -> new IllegalArgumentException("No such file: " + path));
		}
		return store.read(projectId, path);
	}

	/** Paths the explorer shows: every project file except backups, plus the narrative docs not written yet. */
	public List<String> listFiles(String projectId) {
		requireProject(projectId);
		Set<String> files = new LinkedHashSet<>();
		for (NarrativeDoc doc : NarrativeDoc.values()) {
			files.add(doc.path());
		}
		store.list(projectId, "").stream().filter(p -> !p.startsWith(".pdlc/")).forEach(files::add);
		return files.stream().sorted().toList();
	}

	/** True once the interview has written the document (a fresh template carries {@code status: empty}). */
	public boolean narrativeStarted(String projectId, NarrativeDoc doc) {
		return !readFile(projectId, doc.path()).contains("status: empty");
	}

	/** The document without front matter and comments; empty string while it is only a template. */
	public String narrativeBody(String projectId, NarrativeDoc doc) {
		if (!narrativeStarted(projectId, doc)) {
			return "";
		}
		String text = readFile(projectId, doc.path());
		if (text.startsWith("---")) {
			int end = text.indexOf("\n---", 3);
			text = end < 0 ? text : text.substring(end + 4);
		}
		return NARRATIVE_COMMENT.matcher(text).replaceAll("").strip();
	}

	/** The project's {@code implementation/} folder on disk, if the store has one and it exists. */
	public java.util.Optional<java.nio.file.Path> implementationDir(String projectId) {
		requireProject(projectId);
		return store.localPath(projectId).map(p -> p.resolve("implementation")).filter(java.nio.file.Files::isDirectory);
	}

	/** Reads a file that may not exist (caches under .pdlc/); null when absent. */
	public String readIfExists(String projectId, String path) {
		requireProject(projectId);
		return store.exists(projectId, path) ? store.read(projectId, path) : null;
	}

	/** Writes an internal file (caches under .pdlc/) with no backup, version or changelog line. */
	public void writeInternal(String projectId, String path, String content) {
		requireProject(projectId);
		store.write(projectId, path, content);
	}

	/** Interview steps recorded as complete in the front matter of the narrative documents. */
	public Set<InterviewStep> completedSteps(String projectId) {
		Set<InterviewStep> done = new LinkedHashSet<>();
		for (NarrativeDoc doc : NarrativeDoc.values()) {
			for (String name : doneNames(readFile(projectId, doc.path())).split(",")) {
				for (InterviewStep step : InterviewStep.values()) {
					if (step.doc() == doc && step.name().equalsIgnoreCase(name.strip())) {
						done.add(step);
					}
				}
			}
		}
		return done;
	}

	/** Records which steps are complete (front matter only: no version bump, no backup). */
	public void setCompletedSteps(String projectId, Set<InterviewStep> done) {
		for (NarrativeDoc doc : NarrativeDoc.values()) {
			String names = Arrays.stream(InterviewStep.values())
				.filter(s -> s.doc() == doc && done.contains(s))
				.map(InterviewStep::name)
				.collect(Collectors.joining(", "));
			String raw = readFile(projectId, doc.path());
			if (names.equals(doneNames(raw))) {
				continue;
			}
			String stripped = NARRATIVE_DONE_LINE.matcher(raw).replaceFirst("");
			int close = stripped.indexOf("\n---", 3);
			if (close >= 0) {
				store.write(projectId, doc.path(), stripped.substring(0, close + 1)
						+ (names.isEmpty() ? "" : "done: " + names + "\n") + stripped.substring(close + 1));
			}
		}
	}

	private static String doneNames(String raw) {
		Matcher m = NARRATIVE_DONE.matcher(raw);
		return m.find() ? m.group(1).strip() : "";
	}

	private static String doneLine(String raw) {
		String names = doneNames(raw);
		return names.isEmpty() ? "" : "done: " + names + "\n";
	}

	/** Replaces a narrative document with a new body: backs up the old file, bumps the version, logs the change. */
	public void writeNarrative(String projectId, NarrativeDoc doc, String body, String reason) {
		String existing = readFile(projectId, doc.path());
		Matcher version = NARRATIVE_VERSION.matcher(existing);
		int next = version.find() ? Integer.parseInt(version.group(1)) + 1 : 1;
		if (store.exists(projectId, doc.path())) {
			store.write(projectId, HISTORY + doc.path().replace('/', '_') + "." + System.currentTimeMillis(), existing);
		}
		store.write(projectId, doc.path(),
				"---\nid: " + doc.docId() + "\nversion: " + next + "\nstatus: draft\n" + doneLine(existing) + "---\n" + body.strip() + "\n");
		String why = reason == null || reason.isBlank() ? "" : " | " + reason.replaceAll("\\s+", " ").trim();
		log(projectId, doc.path() + " | v" + next + " | updated" + why);
	}

	public List<MdEntry> entries(String projectId, String file) {
		requireIdFile(file);
		return MdDocument.parse(readFile(projectId, file)).entries();
	}

	/** Next free id in an md file, e.g. REQ-004. */
	public String nextId(String projectId, String file) {
		requireIdFile(file);
		return MdDocument.parse(readFile(projectId, file)).nextId(ID_PREFIX.get(file));
	}

	/** Validates the ops and returns the patched file without writing anything (for Apply/Reject previews). */
	public PatchResult preview(String projectId, String file, List<PatchOp> ops) {
		return previewAll(projectId, List.of(new FilePatch(file, ops))).get(file);
	}

	public PatchResult apply(String projectId, String file, String reason, List<PatchOp> ops) {
		return applyAll(projectId, reason, List.of(new FilePatch(file, ops))).get(file);
	}

	/**
	 * Applies the patches to in-memory copies of the md files and validates the result as a whole (so a new
	 * CMP may satisfy a REQ added in the same set). Returns the patched content of every touched file.
	 */
	public Map<String, PatchResult> previewAll(String projectId, List<FilePatch> patches) {
		Map<String, MdDocument> docs = new LinkedHashMap<>();
		docs.put(REQ_FILE, MdDocument.parse(readFile(projectId, REQ_FILE)));
		docs.put(CMP_FILE, MdDocument.parse(readFile(projectId, CMP_FILE)));
		Set<String> touched = new LinkedHashSet<>();
		for (FilePatch patch : patches) {
			requireIdFile(patch.file());
			String prefix = ID_PREFIX.get(patch.file());
			for (PatchOp op : patch.ops()) {
				if (op.id() == null || !op.id().startsWith(prefix + "-")) {
					throw new IllegalArgumentException("Id '" + op.id() + "' does not belong in " + patch.file()
							+ " (expected " + prefix + "-###)");
				}
				docs.get(patch.file()).apply(op);
			}
			touched.add(patch.file());
		}
		touched.forEach(file -> docs.get(file).bumpVersion());
		validate(docs.get(REQ_FILE), docs.get(CMP_FILE));
		Map<String, PatchResult> results = new LinkedHashMap<>();
		touched.forEach(file -> results.put(file, new PatchResult(docs.get(file).toString(), version(docs.get(file)))));
		return results;
	}

	/** Same as {@link #previewAll} but writes: for each file a backup, the new content and a changelog line. */
	public Map<String, PatchResult> applyAll(String projectId, String reason, List<FilePatch> patches) {
		Map<String, PatchResult> results = previewAll(projectId, patches);
		String why = reason == null || reason.isBlank() ? "" : " | " + reason.replaceAll("\\s+", " ").trim();
		results.forEach((file, patched) -> {
			store.write(projectId, HISTORY + file.replace('/', '_') + "." + System.currentTimeMillis(),
					store.read(projectId, file));
			store.write(projectId, file, patched.content());
			String summary = patches.stream()
				.filter(p -> p.file().equals(file))
				.flatMap(p -> p.ops().stream())
				.map(o -> o.op() + " " + o.id())
				.collect(Collectors.joining("; "));
			log(projectId, file + " | v" + patched.version() + " | " + (summary.isEmpty() ? "no-op" : summary) + why);
		});
		return results;
	}

	/** Restores the most recent backup of a file. */
	public void undo(String projectId, String file) {
		if (NarrativeDoc.byPath(file).isEmpty()) {
			requireIdFile(file);
		}
		requireProject(projectId);
		String prefix = HISTORY + file.replace('/', '_') + ".";
		String latest = store.list(projectId, prefix)
			.stream()
			.max(Comparator.comparingLong(p -> Long.parseLong(p.substring(prefix.length()))))
			.orElseThrow(() -> new IllegalArgumentException("Nothing to undo for " + file));
		store.write(projectId, file, store.read(projectId, latest));
		store.delete(projectId, latest);
		log(projectId, file + " | undo");
	}

	/** Cross-checks that an md file is consistent with the rest of the project; throws on the first problem. */
	private void validate(MdDocument reqDoc, MdDocument cmpDoc) {
		Set<String> reqIds = new HashSet<>();
		reqDoc.entries().forEach(e -> reqIds.add(e.id()));
		Set<String> cmpIds = new HashSet<>();
		cmpDoc.entries().forEach(e -> cmpIds.add(e.id()));
		for (MdEntry cmp : cmpDoc.entries()) {
			String layer = cmp.attrs().get("layer");
			if (layer == null || !LAYERS.contains(layer)) {
				throw new IllegalArgumentException(cmp.id() + ": layer must be one of " + LAYERS + " (was " + layer + ")");
			}
			for (String req : cmp.list("satisfies")) {
				if (!reqIds.contains(req)) {
					throw new IllegalArgumentException(cmp.id() + " satisfies unknown " + req);
				}
			}
			for (String link : cmp.list("links")) {
				if (!cmpIds.contains(link)) {
					throw new IllegalArgumentException(cmp.id() + " links to unknown " + link);
				}
			}
		}
	}

	private int version(MdDocument doc) {
		return doc.frontMatter().get("version") instanceof Number n ? n.intValue() : 0;
	}

	private void log(String projectId, String message) {
		String line = "- " + Instant.now().toString().replaceAll("\\.\\d+Z$", "Z") + " | " + message + "\n";
		store.write(projectId, CHANGELOG, store.read(projectId, CHANGELOG) + line);
	}

	private void requireIdFile(String file) {
		if (!ID_PREFIX.containsKey(file)) {
			throw new IllegalArgumentException("Patchable files: " + ID_PREFIX.keySet());
		}
	}

	private void requireProject(String projectId) {
		checkProjectId(projectId);
		if (store.list(projectId, "").isEmpty()) {
			throw new IllegalArgumentException("No such project: " + projectId);
		}
	}

	private void checkProjectId(String projectId) {
		if (projectId == null || !PROJECT_ID.matcher(projectId).matches()) {
			throw new IllegalArgumentException("Project id must match " + PROJECT_ID.pattern());
		}
	}

}

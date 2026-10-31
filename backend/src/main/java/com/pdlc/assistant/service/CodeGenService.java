package com.pdlc.assistant.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pdlc.assistant.dto.CodePlan;
import com.pdlc.assistant.dto.GeneratedFile;
import com.pdlc.assistant.dto.LlmRequest;
import com.pdlc.assistant.model.CodeFile;
import com.pdlc.assistant.model.MdEntry;
import com.pdlc.assistant.model.NarrativeDoc;

/**
 * Generates a high-level code skeleton into the project's {@code implementation/} folder from the md files
 * (technical decisions, requirements, building blocks): structure and signatures, with each component's main class
 * depending on the classes of the components it links to, so the code mirrors the design. Developers write the
 * real code on top; the code watcher then keeps the design diagram in step with what they change. Two steps: one schema-enforced call plans the files, then one call
 * writes each file. Every generated file is recorded with its component and hash in {@code .pdlc/generated.json}:
 * a regenerate never overwrites a file you have changed, and a later code sync uses the record to map the code
 * back onto the same components instead of inventing new ones.
 */
@Service
public class CodeGenService {

	private static final Logger log = LoggerFactory.getLogger(CodeGenService.class);

	public static final String ROOT = "implementation/";

	private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_.@-]+(/[A-Za-z0-9_.@-]+)*");

	private static final int PLAN_TOKENS = 3500;

	private static final int FILE_TOKENS = 2000;

	private static final String PLAN_SYSTEM = """
			You are a senior engineer scaffolding an application from its design. Produce a HIGH-LEVEL SKELETON, not \
			the finished application: the folder and package structure, one main class or module per component, and \
			the build and run files. Developers will write the real code on top of it. Use exactly the technology in \
			the technical decisions (language, framework, build tool, database, cache, authentication, deployment); \
			where they are silent, pick the simplest common choice.
			Return the files, at most %d:
			- path: relative to the application root, forward slashes, no leading slash. Each component's code lives \
			in its own folder or package. A component with a link to another component (see 'links' in the component \
			list) has a main class that depends on the main class of the component it links to, so the code structure \
			mirrors the design. A database, cache or cloud component has no code of its own: it appears as \
			configuration and as a dependency in the build file.
			- purpose: one sentence saying what the developer will implement there.
			- component: the CMP id the file belongs to (for example CMP-002); empty for build, config and docs.
			- api: the main public names other files use (class name, key method signatures); short.
			Also include the application entry point, each build file in the folder of what it builds \
			(backend/pom.xml next to backend/src, frontend/package.json), application configuration, a README.md with \
			how to build and run it, a .gitignore, and a Dockerfile when deployment uses containers. Keep it small: \
			no tests, no business logic, no helper or utility files that no component needs.""";

	private static final String FILE_SYSTEM = """
			You write ONE file of a code SKELETON that developers will fill in. Put the complete file in the content \
			field: no markdown fences. Real structure, no business logic: correct package and imports, class and \
			method signatures, a short comment saying what the developer must implement, and minimal bodies (an empty \
			body, or throw new UnsupportedOperationException("TODO")). If the plan says this component's class depends \
			on another component's class, import it and hold it as a field. Use exactly the technical decisions; \
			Spring Boot 3 and Jakarta EE use jakarta.* packages, never javax.*. The file must be syntactically valid \
			and refer to other planned files only through the paths, packages and names in the plan. Keep it short, \
			well under 60 lines. A Maven pom.xml for Spring Boot uses spring-boot-starter-parent as its parent with \
			the version from the technical decisions and includes spring-boot-maven-plugin; a package.json has scripts \
			(dev, build, start) and a version for every dependency. Build files list every library the code imports, \
			which is given to you.""";

	private final ProjectService projects;

	private final LlmGateway llm;

	private final GenerationRecord records;

	private final CodeWatchService watcher;

	private final CodeSyncService sync;

	private final CodeExtractorRunner extractor;

	private final String model;

	private final int maxFiles;


	public CodeGenService(ProjectService projects, LlmGateway llm, GenerationRecord records, CodeWatchService watcher,
			CodeSyncService sync, CodeExtractorRunner extractor, @Value("${pdlc.llm.codegen-model:}") String model,
			@Value("${pdlc.code.gen-max-files:16}") int maxFiles) {
		this.projects = projects;
		this.llm = llm;
		this.records = records;
		this.watcher = watcher;
		this.sync = sync;
		this.extractor = extractor;
		this.model = model.isBlank() ? null : model;
		this.maxFiles = maxFiles;
	}

	/** Returns a short markdown report; files are written as they are produced. */
	public String generate(String projectId, Consumer<String> status, BooleanSupplier cancelled) {
		List<MdEntry> components = projects.entries(projectId, ProjectService.CMP_FILE);
		if (components.isEmpty()) {
			throw new IllegalArgumentException(
					"There is no design to build from yet. Finish the interview and type 'generate requirements' first.");
		}
		List<MdEntry> reqs = projects.entries(projectId, ProjectService.REQ_FILE);
		String tech = cap(projects.narrativeBody(projectId, NarrativeDoc.TECH_DECISIONS), 3000);
		String context = "Product:\n" + cap(projects.narrativeBody(projectId, NarrativeDoc.PRODUCT_INTENT), 1500)
				+ "\n\nTechnical decisions:\n" + (tech.isEmpty() ? "(none stated)" : tech) + "\n\nRequirements:\n"
				+ cap(reqs.stream().map(r -> r.id() + ": " + r.text()).collect(Collectors.joining("\n")), 4000)
				+ "\n\nComponents:\n" + cap(components.stream().map(CodeGenService::describe).collect(Collectors.joining("\n")), 5000);

		watcher.pause(projectId);
		try {
			status.accept("Planning the files…");
			CodePlan plan = llm.complete(new LlmRequest("codegen-plan", PLAN_SYSTEM.formatted(maxFiles), context, model, PLAN_TOKENS),
					CodePlan.class);
			List<CodePlan.PlannedFile> files = clean(plan);
			if (files.isEmpty()) {
				throw new IllegalArgumentException("The model did not produce a usable file plan; try again");
			}
			String planText = files.stream()
				.map(f -> "- " + f.path + " — " + f.purpose + (blank(f.api) ? "" : " [api: " + f.api.strip() + "]"))
				.collect(Collectors.joining("\n"));

			Map<String, GenerationRecord.Entry> record = records.load(projectId);
			List<String> written = new ArrayList<>();
			List<String> kept = new ArrayList<>();
			List<CodePlan.PlannedFile> deferred = new ArrayList<>();
			int n = 0;
			for (CodePlan.PlannedFile file : files) {
				n++;
				if (cancelled.getAsBoolean()) {
					break;
				}
				if (isManifest(file.path)) {
					deferred.add(file);
					continue;
				}
				String existing = projects.readIfExists(projectId, ROOT + file.path);
				GenerationRecord.Entry previous = record.get(file.path);
				if (existing != null && (previous == null || !previous.hash().equals(sha(existing)))) {
					kept.add(file.path);
					continue;
				}
				status.accept("Writing " + file.path + " (" + n + " of " + files.size() + ")…");
				String input = context + "\n\nPlan:\n" + planText + "\n\nWrite this file: " + file.path + "\nPurpose: "
						+ file.purpose + (blank(file.component) ? "" : "\nComponent: " + file.component);
				GeneratedFile generated = llm.complete(
						new LlmRequest("codegen-file", FILE_SYSTEM, input, model, FILE_TOKENS), GeneratedFile.class);
				String content = unfence(generated.content);
				if (content.isBlank()) {
					kept.add(file.path + " (empty answer)");
					continue;
				}
				projects.writeInternal(projectId, ROOT + file.path, content);
				record.put(file.path, new GenerationRecord.Entry(
						blank(file.component) ? inferComponent(file.path, components) : file.component.strip(), sha(content)));
				records.save(projectId, record);
				written.add(file.path);
			}
			List<String> filled = List.of();
			if (!written.isEmpty() && !cancelled.getAsBoolean()) {
				status.accept("Checking the generated code for missing classes and files…");
				filled = fillGaps(projectId, record, planText, context, status, cancelled);
				written.addAll(filled);
			}
			if (!written.isEmpty() && !cancelled.getAsBoolean()) {
				List<String> repaired = repairSyntax(projectId, record, planText, context, status, cancelled);
				if (!repaired.isEmpty()) {
					log.info("codegen project={} repaired syntax of {}", projectId, repaired);
				}
			}
			if (!deferred.isEmpty() && !cancelled.getAsBoolean()) {
				status.accept("Writing the build files from the libraries the code actually imports…");
				written.addAll(writeManifests(projectId, deferred, record, planText, context, kept, status));
			}
			log.info("codegen project={} planned={} written={} kept={} gapFilled={}", projectId, files.size(),
					written.size(), kept.size(), filled.size());

			StringBuilder report = new StringBuilder("Generated %d files into `implementation/` (%d planned%s).".formatted(
					written.size(), files.size(),
					filled.isEmpty() ? "" : ", " + filled.size() + " added after checking for missing classes"));
			if (!kept.isEmpty()) {
				report.append("\n\nNot written (you changed them, or the answer was empty): ")
					.append(String.join(", ", kept))
					.append('.');
			}
			if (cancelled.getAsBoolean()) {
				report.append("\n\nStopped early.");
			}
			if (!written.isEmpty()) {
				status.accept("Reading the generated code back into the design…");
				report.append("\n\n").append(sync.syncAndApply(projectId, projects.implementationDir(projectId).orElseThrow().toString(), status));
			}
			return report.toString();
		}
		finally {
			watcher.resume(projectId);
		}
	}

	/**
	 * A source file the plan gave no component belongs to the one component of its layer, judged by its top folder
	 * (frontend/, backend/); otherwise a code sync would invent an extra component for it. Empty when unclear.
	 */
	private static String inferComponent(String path, List<MdEntry> components) {
		String top = path.contains("/") ? path.substring(0, path.indexOf('/')).toLowerCase() : "";
		String layer = switch (top) {
			case "frontend", "client", "web", "ui" -> "frontend";
			case "backend", "server", "api", "service" -> "backend";
			default -> "";
		};
		List<MdEntry> candidates = components.stream().filter(c -> layer.equals(c.attrs().get("layer"))).toList();
		return candidates.size() == 1 ? candidates.get(0).id() : "";
	}

	/** A file the generated code refers to but nobody wrote, and the file that refers to it. */
	private record Gap(String path, String usedBy, String what) {
	}

	private static final int MAX_GAP_FILES = 8;

	private static final java.util.Set<String> JAVA_LANG = java.util.Set.of("String", "Integer", "Long", "Double",
			"Float", "Boolean", "Byte", "Short", "Character", "Object", "Number", "Math", "System", "Exception",
			"RuntimeException", "Throwable", "Error", "IllegalArgumentException", "IllegalStateException",
			"NullPointerException", "UnsupportedOperationException", "Iterable", "Comparable", "Runnable", "Thread",
			"Void", "StringBuilder", "StringBuffer", "Class", "Enum", "Record", "CharSequence", "AutoCloseable",
			"InterruptedException", "AssertionError", "ArithmeticException", "IndexOutOfBoundsException",
			"ClassCastException", "SecurityException", "Cloneable", "Process", "ProcessBuilder", "Runtime");

	/**
	 * One round of "tree-sitter checks what the model forgot": reads the generated sources back, finds Java types
	 * that are used but declared nowhere and relative JS imports that point at no file, and generates exactly
	 * those files, showing the model the file that uses them so the members match how they are used.
	 */
	private List<String> fillGaps(String projectId, Map<String, GenerationRecord.Entry> record, String planText,
			String context, Consumer<String> status, BooleanSupplier cancelled) {
		java.nio.file.Path root = projects.implementationDir(projectId).orElseThrow();
		List<String> sources = record.keySet().stream().filter(p -> p.matches(".*\\.(java|js|jsx|ts|tsx|mjs)$")).toList();
		Map<String, CodeFile> facts = new LinkedHashMap<>();
		extractor.extract(root, sources).files().forEach((path, f) -> facts.put(path, f.with(path, null)));
		List<Gap> gaps = gaps(facts);
		List<String> filled = new ArrayList<>();
		for (Gap gap : gaps) {
			if (cancelled.getAsBoolean() || filled.size() >= MAX_GAP_FILES) {
				break;
			}
			status.accept("Adding missing " + gap.path() + "…");
			String using = projects.readIfExists(projectId, ROOT + gap.usedBy());
			String input = context + "\n\nPlan:\n" + planText + "\n\nWrite this file, which the plan forgot: " + gap.path()
					+ "\nPurpose: " + gap.what() + "\nIt is used by " + gap.usedBy()
					+ ", shown below: give it exactly the members that file needs.\n\n" + cap(using == null ? "" : using, 9000);
			GeneratedFile generated = llm.complete(new LlmRequest("codegen-gap", FILE_SYSTEM, input, model, FILE_TOKENS),
					GeneratedFile.class);
			String content = unfence(generated.content);
			if (!content.isBlank()) {
				projects.writeInternal(projectId, ROOT + gap.path(), content);
				GenerationRecord.Entry user = record.get(gap.usedBy());
				record.put(gap.path(), new GenerationRecord.Entry(user == null || blank(user.component())
						? inferComponent(gap.path(), projects.entries(projectId, ProjectService.CMP_FILE)) : user.component(),
						sha(content)));
				records.save(projectId, record);
				filled.add(gap.path());
			}
		}
		return filled;
	}

	private static final int MAX_REPAIRS = 6;

	/**
	 * tree-sitter marks a file it could not parse cleanly as {@code partial}. A generated file like that is almost
	 * always cut off or malformed, so it is generated once more, told what went wrong and shown the end of the broken
	 * attempt. Returns the paths that were rewritten.
	 */
	private List<String> repairSyntax(String projectId, Map<String, GenerationRecord.Entry> record, String planText,
			String context, Consumer<String> status, BooleanSupplier cancelled) {
		java.nio.file.Path root = projects.implementationDir(projectId).orElseThrow();
		List<String> sources = record.keySet().stream().filter(p -> p.matches(".*\\.(java|js|jsx|ts|tsx|mjs)$")).toList();
		List<String> broken = extractor.extract(root, sources)
			.files()
			.entrySet()
			.stream()
			.filter(e -> e.getValue().partial())
			.map(Map.Entry::getKey)
			.limit(MAX_REPAIRS)
			.toList();
		List<String> repaired = new ArrayList<>();
		for (String path : broken) {
			if (cancelled.getAsBoolean()) {
				break;
			}
			status.accept("Rewriting " + path + " (it had a syntax error)…");
			String previous = projects.readIfExists(projectId, ROOT + path);
			GenerationRecord.Entry entry = record.get(path);
			String input = context + "\n\nPlan:\n" + planText + "\n\nWrite this file: " + path
					+ "\nYour previous attempt had a syntax error: it was cut off or malformed. Its last lines were:\n"
					+ cap(tail(previous == null ? "" : previous, 800), 900) + "\nWrite the complete, syntactically valid file.";
			GeneratedFile generated = llm.complete(new LlmRequest("codegen-repair", FILE_SYSTEM, input, model, FILE_TOKENS),
					GeneratedFile.class);
			String content = unfence(generated.content);
			if (!content.isBlank()) {
				projects.writeInternal(projectId, ROOT + path, content);
				record.put(path, new GenerationRecord.Entry(entry == null ? "" : entry.component(), sha(content)));
				records.save(projectId, record);
				repaired.add(path);
			}
		}
		return repaired;
	}

	private static String tail(String text, int chars) {
		return text.length() <= chars ? text : text.substring(text.length() - chars);
	}

	private static boolean isManifest(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1);
		return name.equals("pom.xml") || name.equals("package.json") || name.startsWith("build.gradle");
	}

	/**
	 * Build files are written last, so they can list the libraries that the generated code really imports (found
	 * by tree-sitter) instead of the ones the model guessed it would need while planning.
	 */
	private List<String> writeManifests(String projectId, List<CodePlan.PlannedFile> manifests,
			Map<String, GenerationRecord.Entry> record, String planText, String context, List<String> kept,
			Consumer<String> status) {
		java.nio.file.Path root = projects.implementationDir(projectId).orElseThrow();
		List<String> sources = record.keySet().stream().filter(p -> p.matches(".*\\.(java|js|jsx|ts|tsx|mjs)$")).toList();
		Map<String, CodeFile> facts = new LinkedHashMap<>();
		extractor.extract(root, sources).files().forEach((path, f) -> facts.put(path, f.with(path, null)));
		List<String> written = new ArrayList<>();
		for (CodePlan.PlannedFile file : manifests) {
			String existing = projects.readIfExists(projectId, ROOT + file.path);
			GenerationRecord.Entry previous = record.get(file.path);
			if (existing != null && (previous == null || !previous.hash().equals(sha(existing)))) {
				kept.add(file.path);
				continue;
			}
			// only the libraries imported below this build file's folder
			String dir = file.path.contains("/") ? file.path.substring(0, file.path.lastIndexOf('/') + 1) : "";
			List<String> libraries = externalImports(facts.values().stream().filter(f -> f.path().startsWith(dir)).toList());
			status.accept("Writing " + file.path + "…");
			String input = context + "\n\nPlan:\n" + planText + "\n\nLibraries imported by the code under "
					+ (dir.isEmpty() ? "the application root" : dir) + ":\n"
					+ (libraries.isEmpty() ? "(none besides the standard library)" : String.join("\n", libraries))
					+ "\n\nWrite this file: " + file.path + "\nPurpose: " + file.purpose;
			GeneratedFile generated = llm.complete(new LlmRequest("codegen-build", FILE_SYSTEM, input, model, FILE_TOKENS),
					GeneratedFile.class);
			String content = unfence(generated.content);
			if (!content.isBlank()) {
				projects.writeInternal(projectId, ROOT + file.path, content);
				record.put(file.path, new GenerationRecord.Entry(blank(file.component) ? "" : file.component.strip(), sha(content)));
				records.save(projectId, record);
				written.add(file.path);
			}
		}
		return written;
	}

	/** Library names (Java package roots of three segments, npm package names) the given files import. */
	private static List<String> externalImports(java.util.Collection<CodeFile> files) {
		java.util.Set<String> internal = new java.util.HashSet<>();
		files.stream().filter(f -> "java".equals(f.lang()) && f.pkg() != null && !f.pkg().isEmpty()).forEach(f -> {
			String[] parts = f.pkg().split("\\.");
			internal.add(parts.length >= 2 ? parts[0] + "." + parts[1] : f.pkg());
		});
		java.util.Set<String> libs = new java.util.TreeSet<>();
		for (CodeFile f : files) {
			for (CodeFile.Import i : f.imports()) {
				String spec = i.spec();
				if ("java".equals(f.lang())) {
					if (!spec.startsWith("java.") && internal.stream().noneMatch(spec::startsWith)) {
						String[] parts = spec.replace(".*", "").split("\\.");
						libs.add(String.join(".", java.util.Arrays.copyOf(parts, Math.min(3, parts.length))));
					}
				}
				else if (!spec.startsWith(".") && !spec.startsWith("node:")) {
					String[] parts = spec.split("/");
					libs.add(spec.startsWith("@") && parts.length > 1 ? parts[0] + "/" + parts[1] : parts[0]);
				}
			}
		}
		return libs.stream().limit(60).toList();
	}

	/** True when one of the file's {@code java.*} wildcard imports provides the type: the JDK itself can answer. */
	private static boolean jdkWildcard(CodeFile f, String type) {
		for (CodeFile.Import i : f.imports()) {
			if (i.spec().endsWith(".*") && (i.spec().startsWith("java.") || i.spec().startsWith("javax."))) {
				try {
					Class.forName(i.spec().substring(0, i.spec().length() - 1) + type, false, ClassLoader.getPlatformClassLoader());
					return true;
				}
				catch (ClassNotFoundException | LinkageError e) {
					// not in that package
				}
			}
		}
		return false;
	}

	private static List<Gap> gaps(Map<String, CodeFile> facts) {
		java.util.Set<String> declared = new java.util.HashSet<>();
		facts.values().stream().filter(f -> "java".equals(f.lang())).forEach(f -> f.types().forEach(t -> declared.add(t.name())));
		// how many files use each type: a project class is usually shared, a library class named once is probably a library
		Map<String, Integer> uses = new LinkedHashMap<>();
		facts.values().stream().filter(f -> "java".equals(f.lang())).forEach(f -> f.usedTypes().forEach(t -> uses.merge(t, 1, Integer::sum)));
		Map<String, Gap> found = new LinkedHashMap<>();
		java.util.Set<String> projectRoots = new java.util.HashSet<>();
		facts.values().stream().filter(f -> "java".equals(f.lang()) && f.pkg() != null && !f.pkg().isEmpty()).forEach(f -> {
			String[] parts = f.pkg().split("\\.");
			projectRoots.add(parts.length >= 2 ? parts[0] + "." + parts[1] : f.pkg());
		});
		for (CodeFile f : facts.values()) {
			if ("java".equals(f.lang())) {
				// an explicit import of a project class that no file declares
				for (CodeFile.Import i : f.imports()) {
					String spec = i.spec();
					String simple = spec.substring(spec.lastIndexOf('.') + 1);
					boolean ours = !spec.endsWith("*") && projectRoots.stream().anyMatch(r -> spec.startsWith(r + "."));
					if (ours && !declared.contains(simple) && f.pkg() != null && !f.pkg().isEmpty()) {
						String name = f.path().substring(f.path().lastIndexOf('/') + 1);
						int tail = (f.pkg().replace('.', '/') + "/" + name).length();
						String sourceRoot = f.path().length() > tail ? f.path().substring(0, f.path().length() - tail) : "";
						String target = sourceRoot + spec.replace('.', '/') + ".java";
						found.putIfAbsent(target, new Gap(target, f.path(), "The Java class " + spec));
					}
				}
				// a wildcard import of a library package hides where a name comes from
				boolean opaque = f.imports().stream().anyMatch(i -> i.spec().endsWith(".*") && !i.spec().startsWith("java."));
				java.util.Set<String> imported = new java.util.HashSet<>();
				f.imports().forEach(i -> imported.add(i.spec().substring(i.spec().lastIndexOf('.') + 1)));
				String dir = f.path().substring(0, f.path().lastIndexOf('/'));
				for (String type : f.usedTypes()) {
					if (!declared.contains(type) && !imported.contains(type) && !JAVA_LANG.contains(type)
							&& type.length() > 1 && (!opaque || uses.getOrDefault(type, 0) >= 2) && !jdkWildcard(f, type)) {
						found.putIfAbsent(dir + "/" + type + ".java", new Gap(dir + "/" + type + ".java", f.path(),
								"The Java type " + type + " in package " + f.pkg()));
					}
				}
			}
			else {
				String dir = f.path().contains("/") ? f.path().substring(0, f.path().lastIndexOf('/')) : "";
				for (CodeFile.Import i : f.imports()) {
					String spec = i.spec();
					if (!spec.startsWith(".") || spec.matches(".*\\.(css|scss|json|svg|png|jpg|ico|md)$")) {
						continue;
					}
					String base = java.nio.file.Path.of(dir).resolve(spec).normalize().toString().replace('\\', '/');
					boolean exists = facts.containsKey(base) || java.util.stream.Stream.of(".js", ".jsx", ".ts", ".tsx", ".mjs")
						.anyMatch(ext -> facts.containsKey(base + ext) || facts.containsKey(base + "/index" + ext));
					if (!exists && !base.startsWith("..")) {
						String target = spec.matches(".*\\.(js|jsx|ts|tsx|mjs)$") ? base : base + (f.lang().endsWith("x") ? ".jsx" : ".js");
						found.putIfAbsent(target, new Gap(target, f.path(), "The module imported as '" + spec + "'"));
					}
				}
			}
		}
		return List.copyOf(found.values());
	}

	/** Valid, unique, safe paths only, capped at the limit; the model's component ids are kept as given. */
	private List<CodePlan.PlannedFile> clean(CodePlan plan) {
		List<CodePlan.PlannedFile> out = new ArrayList<>();
		if (plan == null || plan.files == null) {
			return out;
		}
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (CodePlan.PlannedFile f : plan.files) {
			if (f == null || f.path == null) {
				continue;
			}
			String path = f.path.strip().replace('\\', '/').replaceFirst("^(\\./|/)+", "").replaceFirst("^implementation/", "");
			boolean safe = SAFE_PATH.matcher(path).matches() && !path.contains("..") && !path.startsWith(".pdlc");
			if (safe && seen.add(path) && out.size() < maxFiles) {
				f.path = path;
				f.purpose = blank(f.purpose) ? "Part of the application" : f.purpose.strip();
				out.add(f);
			}
		}
		return out;
	}

	private static String describe(MdEntry c) {
		StringBuilder out = new StringBuilder(c.id() + " " + c.text());
		for (String key : List.of("layer", "language", "technology", "responsibility", "satisfies", "links")) {
			String v = c.attrs().get(key);
			if (v != null && !v.isBlank()) {
				out.append(" | ").append(key).append(": ").append(v);
			}
		}
		return out.toString();
	}

	/** The model sometimes wraps a file in a markdown fence despite being told not to. */
	private static String unfence(String content) {
		if (content == null) {
			return "";
		}
		String text = content.strip();
		if (text.startsWith("```")) {
			int eol = text.indexOf('\n');
			text = eol < 0 ? "" : text.substring(eol + 1);
			int end = text.lastIndexOf("```");
			text = end < 0 ? text : text.substring(0, end);
		}
		return text.strip() + "\n";
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static String cap(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "…";
	}

	private static String sha(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}

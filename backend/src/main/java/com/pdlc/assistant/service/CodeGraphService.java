package com.pdlc.assistant.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pdlc.assistant.model.CodeFile;
import com.pdlc.assistant.service.CodeInventoryService.External;

/**
 * Steps 7.2 to 7.3 after extraction, no LLM: resolves imports to files, groups files into a handful of components
 * by package/folder boundary, and aggregates file-level imports into component-level edges. Cluster keys come
 * from paths, so they are stable between runs and are what a CMP's {@code code:} attribute refers to.
 */
@Service
public class CodeGraphService {

	/** A building block found in code. {@code files} are paths; {@code layer} is a first guess the LLM may refine. */
	public record Cluster(String key, List<String> files, String layer) {
	}

	/** {@code from} imports from {@code to}; {@code evidence} is the first import that caused it (file:line). */
	public record Edge(String from, String to, int weight, String evidence) {
	}

	public record Graph(List<Cluster> clusters, List<Edge> edges, Map<String, String> fileCluster,
			Map<String, Set<String>> externalUsers, List<String> notes) {
	}

	private static final List<String> JS_EXT = List.of(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs");

	private static final Pattern SECURITY = Pattern.compile("(?i)security|auth");

	private static final Pattern FRONTEND_PATH = Pattern
		.compile("(?i)(^|/)(frontend|client|web|ui|components|pages|views|hooks)(/|$)");

	private static final Set<String> ROOT_DIRS = Set.of("src", "app", "lib", "source");

	private final int maxComponents;

	public CodeGraphService(@Value("${pdlc.code.max-components:12}") int maxComponents) {
		this.maxComponents = maxComponents;
	}

	/**
	 * @param componentOf files that code generation wrote for a design component (path to CMP id); each such
	 * component becomes one cluster {@code cmp:CMP-001}, so generated code maps back onto the same design
	 */
	public Graph build(Map<String, CodeFile> files, List<External> externals, Map<String, String> componentOf) {
		List<String> notes = new ArrayList<>();
		Map<String, Set<String>> clusterFiles = cluster(files, componentOf, notes);

		Map<String, String> fileCluster = new LinkedHashMap<>();
		clusterFiles.forEach((key, paths) -> paths.forEach(p -> fileCluster.put(p, key)));

		Resolver resolver = new Resolver(files);
		Map<String, int[]> counts = new TreeMap<>();
		Map<String, String> evidence = new LinkedHashMap<>();
		for (CodeFile file : files.values()) {
			String from = fileCluster.get(file.path());
			for (CodeFile.Import imp : file.imports()) {
				for (String target : resolver.resolve(file, imp)) {
					String to = fileCluster.get(target);
					if (to != null && !to.equals(from) && !target.equals(file.path())) {
						String edge = from + "\t" + to;
						counts.computeIfAbsent(edge, k -> new int[1])[0]++;
						evidence.putIfAbsent(edge, file.path() + ":" + imp.line());
					}
				}
			}
		}
		List<Edge> edges = new ArrayList<>();
		counts.forEach((edge, n) -> {
			String[] ends = edge.split("\t");
			edges.add(new Edge(ends[0], ends[1], n[0], evidence.get(edge)));
		});

		Map<String, Set<String>> externalUsers = new LinkedHashMap<>();
		for (External external : externals) {
			Set<String> users = new LinkedHashSet<>();
			for (CodeFile file : files.values()) {
				if (file.imports().stream().anyMatch(i -> matchesImport(i.spec(), external.imports()))) {
					users.add(fileCluster.get(file.path()));
				}
			}
			externalUsers.put(external.name(), users);
		}

		List<Cluster> clusters = new ArrayList<>();
		clusterFiles.forEach((key, paths) -> clusters.add(new Cluster(key, List.copyOf(paths), layerOf(key, paths, files))));
		return new Graph(clusters, edges, fileCluster, externalUsers, notes);
	}

	// ---------- clustering ----------

	private record Placed(String module, List<String> segments) {
	}

	/** Cluster key to the files in it, collapsed to at most {@code maxComponents} clusters. */
	private Map<String, Set<String>> cluster(Map<String, CodeFile> files, Map<String, String> componentOf,
			List<String> notes) {
		Map<String, Set<String>> clusters = new TreeMap<>();
		Map<String, List<CodeFile>> byModule = new LinkedHashMap<>();
		for (CodeFile file : files.values()) {
			String component = componentOf.get(file.path());
			if (component != null) {
				clusters.computeIfAbsent("cmp:" + component, k -> new java.util.TreeSet<>()).add(file.path());
			}
			else {
				byModule.computeIfAbsent(moduleOf(file.path()), k -> new ArrayList<>()).add(file);
			}
		}
		// the shared package prefix is a property of the whole module, including files already placed in a component:
		// computed from only a few new files it would swallow their package name
		Map<String, List<CodeFile>> allByModule = new LinkedHashMap<>();
		for (CodeFile file : files.values()) {
			allByModule.computeIfAbsent(moduleOf(file.path()), k -> new ArrayList<>()).add(file);
		}
		byModule.forEach((module, moduleFiles) -> {
			int prefix = commonJavaPackageLength(allByModule.getOrDefault(module, moduleFiles));
			Map<String, Placed> placed = new LinkedHashMap<>();
			moduleFiles.forEach(f -> placed.put(f.path(), new Placed(module, segments(f, module, prefix))));

			// first segment; a cluster holding most of the module is split once by the next segment
			Map<String, List<String>> groups = new TreeMap<>();
			placed.forEach((path, p) -> groups.computeIfAbsent(keyOf(p, 1), k -> new ArrayList<>()).add(path));
			Map<String, List<String>> refined = new TreeMap<>();
			groups.forEach((key, paths) -> {
				if (paths.size() > Math.max(8, moduleFiles.size() * 0.4) && groups.size() < 8) {
					paths.forEach(path -> refined.computeIfAbsent(keyOf(placed.get(path), 2), k -> new ArrayList<>()).add(path));
				}
				else {
					refined.computeIfAbsent(key, k -> new ArrayList<>()).addAll(paths);
				}
			});
			refined.forEach((key, paths) -> clusters.computeIfAbsent(key, k -> new java.util.TreeSet<>()).addAll(paths));
		});

		// too many: fold the smallest clusters into a "misc" cluster of their module
		int merged = 0;
		while (clusters.size() > maxComponents) {
			String smallest = clusters.entrySet()
				.stream()
				.filter(e -> !e.getKey().endsWith("misc") && !e.getKey().startsWith("cmp:"))
				.min(Comparator.comparingInt((Map.Entry<String, Set<String>> e) -> e.getValue().size())
					.thenComparing(Map.Entry::getKey))
				.map(Map.Entry::getKey)
				.orElse(null);
			if (smallest == null) {
				break;
			}
			int slash = smallest.indexOf('/');
			String misc = (slash < 0 ? "" : smallest.substring(0, slash) + "/") + "misc";
			clusters.computeIfAbsent(misc, k -> new java.util.TreeSet<>()).addAll(clusters.remove(smallest));
			merged++;
		}
		if (merged > 0) {
			notes.add("Folded " + merged + " small groups of files into 'misc' to keep at most " + maxComponents
					+ " components");
		}
		return clusters;
	}

	/** The part of the path before {@code src/}, e.g. "backend"; empty for a single-module repo. */
	private static String moduleOf(String path) {
		String[] parts = path.split("/");
		for (int i = 0; i < parts.length - 1; i++) {
			if (parts[i].equals("src")) {
				return String.join("/", java.util.Arrays.copyOfRange(parts, 0, i));
			}
		}
		return parts.length > 2 ? parts[0] : "";
	}

	/** Number of leading package segments shared by every Java file of a module (the company/app prefix). */
	private static int commonJavaPackageLength(List<CodeFile> moduleFiles) {
		List<String[]> packages = moduleFiles.stream()
			.filter(f -> "java".equals(f.lang()) && f.pkg() != null && !f.pkg().isEmpty())
			.map(f -> f.pkg().split("\\."))
			.toList();
		if (packages.isEmpty()) {
			return 0;
		}
		int common = packages.get(0).length;
		for (String[] p : packages) {
			int i = 0;
			while (i < common && i < p.length && p[i].equals(packages.get(0)[i])) {
				i++;
			}
			common = i;
		}
		return common;
	}

	private static List<String> segments(CodeFile file, String module, int javaPrefix) {
		List<String> segments = new ArrayList<>();
		if ("java".equals(file.lang())) {
			String[] pkg = file.pkg() == null || file.pkg().isEmpty() ? new String[0] : file.pkg().split("\\.");
			for (int i = javaPrefix; i < pkg.length; i++) {
				segments.add(pkg[i]);
			}
			return segments;
		}
		String rest = module.isEmpty() ? file.path() : file.path().substring(module.length() + 1);
		String[] parts = rest.split("/");
		int start = parts.length > 1 && ROOT_DIRS.contains(parts[0]) ? 1 : 0;
		for (int i = start; i < parts.length - 1; i++) {
			segments.add(parts[i]);
		}
		return segments;
	}

	private static String keyOf(Placed placed, int depth) {
		List<String> s = placed.segments();
		String sub = s.isEmpty() ? "app" : String.join("/", s.subList(0, Math.min(depth, s.size())));
		return placed.module().isEmpty() ? sub : placed.module() + "/" + sub;
	}

	private static String layerOf(String key, Set<String> paths, Map<String, CodeFile> files) {
		if (SECURITY.matcher(key).find()) {
			return "security";
		}
		long java = paths.stream().filter(p -> "java".equals(files.get(p).lang())).count();
		boolean jsx = paths.stream().anyMatch(p -> files.get(p).lang().endsWith("x"));
		if (java > 0) {
			return "backend";
		}
		// JS/TS without server routes runs in the browser (a Node server declares routes)
		boolean serves = paths.stream().anyMatch(p -> !files.get(p).routes().isEmpty());
		return jsx || FRONTEND_PATH.matcher(key).find() || !serves ? "frontend" : "backend";
	}

	private static boolean matchesImport(String spec, List<String> prefixes) {
		return prefixes.stream().anyMatch(p -> spec.equals(p) || spec.startsWith(p + ".") || spec.startsWith(p + "/"));
	}

	// ---------- import resolution ----------

	/** Maps an import to the project files it refers to (none for libraries). */
	private static final class Resolver {

		private final Map<String, CodeFile> files;

		private final Map<String, String> javaTypes = new LinkedHashMap<>();

		private final Map<String, List<String>> javaPackages = new LinkedHashMap<>();

		Resolver(Map<String, CodeFile> files) {
			this.files = files;
			for (CodeFile f : files.values()) {
				if (!"java".equals(f.lang())) {
					continue;
				}
				javaPackages.computeIfAbsent(f.pkg(), k -> new ArrayList<>()).add(f.path());
				for (CodeFile.TypeInfo t : f.types()) {
					javaTypes.putIfAbsent(f.pkg() == null || f.pkg().isEmpty() ? t.name() : f.pkg() + "." + t.name(),
							f.path());
				}
			}
		}

		List<String> resolve(CodeFile from, CodeFile.Import imp) {
			return "java".equals(from.lang()) ? resolveJava(imp) : resolveJs(from, imp.spec());
		}

		private List<String> resolveJava(CodeFile.Import imp) {
			String spec = imp.spec();
			if (spec.endsWith(".*")) {
				return javaPackages.getOrDefault(spec.substring(0, spec.length() - 2), List.of());
			}
			// a static import or a nested type names a member: walk up until a known type is found
			for (int i = 0; i < 3 && spec.contains("."); i++) {
				String file = javaTypes.get(spec);
				if (file != null) {
					return List.of(file);
				}
				spec = spec.substring(0, spec.lastIndexOf('.'));
			}
			return List.of();
		}

		private List<String> resolveJs(CodeFile from, String spec) {
			if (!spec.startsWith(".")) {
				return List.of();
			}
			String dir = from.path().contains("/") ? from.path().substring(0, from.path().lastIndexOf('/')) : "";
			String base = Path.of(dir).resolve(spec).normalize().toString().replace('\\', '/');
			List<String> candidates = new ArrayList<>();
			candidates.add(base);
			JS_EXT.forEach(ext -> candidates.add(base + ext));
			JS_EXT.forEach(ext -> candidates.add(base + "/index" + ext));
			return candidates.stream().filter(files::containsKey).findFirst().map(List::of).orElse(List.of());
		}

	}

}

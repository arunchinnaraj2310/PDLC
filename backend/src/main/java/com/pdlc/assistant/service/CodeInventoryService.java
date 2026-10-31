package com.pdlc.assistant.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Step 7.1, no LLM: walks the source folder, hashes every source file, and reads the manifests and config files
 * (pom.xml, package.json, application.yml, docker-compose.yml, ...) against {@code code-rules.yml} to find the
 * external systems the code depends on (Redis, PostgreSQL, Kafka, Spring Security, ...), with file:line evidence.
 */
@Service
public class CodeInventoryService {

	/** Source files with their SHA-256, and the external systems the manifests point at. */
	public record Inventory(Map<String, String> sourceHashes, List<External> externals, int skipped) {
	}

	/** An external system found in a manifest; {@code imports} are the source imports that mean "uses it". */
	public record External(String name, String layer, String responsibility, List<String> imports, String evidence) {
	}

	private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target", "build", "dist", "out",
			".idea", ".vscode", ".gradle", "__pycache__", "venv", ".venv", ".next", "coverage", ".pdlc", "bin", ".mvn",
			"vendor");

	private static final Set<String> SOURCE_EXT = Set.of("java", "js", "jsx", "ts", "tsx", "mjs", "cjs");

	private static final Set<String> MANIFESTS = Set.of("pom.xml", "build.gradle", "build.gradle.kts", "package.json",
			"requirements.txt", "docker-compose.yml", "docker-compose.yaml", "application.yml", "application.yaml",
			"application.properties", ".env.example");

	private static final long MAX_FILE_BYTES = 1_000_000;

	private final List<Rule> rules;

	private final int maxFiles;

	private record Rule(String name, String layer, String responsibility, List<String> match, List<String> imports) {
	}

	public CodeInventoryService(@Value("${pdlc.code.max-files:5000}") int maxFiles) {
		this.maxFiles = maxFiles;
		this.rules = loadRules();
	}

	public Inventory scan(Path root) {
		if (!Files.isDirectory(root)) {
			throw new IllegalArgumentException("Not a folder: " + root);
		}
		Map<String, String> hashes = new LinkedHashMap<>();
		List<Path> manifests = new ArrayList<>();
		int[] skipped = { 0 };
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					return dir != root && SKIP_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE
							: FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					String name = file.getFileName().toString();
					String rel = root.relativize(file).toString().replace('\\', '/');
					if (MANIFESTS.contains(name)) {
						manifests.add(file);
					}
					else if (isSource(name, rel)) {
						if (attrs.size() > MAX_FILE_BYTES) {
							skipped[0]++;
						}
						else if (hashes.size() >= maxFiles) {
							throw new IllegalArgumentException("More than " + maxFiles
									+ " source files: point at a smaller folder or raise pdlc.code.max-files");
						}
						else {
							hashes.put(rel, sha256(file));
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (hashes.isEmpty()) {
			throw new IllegalArgumentException("No Java or JavaScript/TypeScript source files found in " + root);
		}
		return new Inventory(hashes, externals(root, manifests), skipped[0]);
	}

	/**
	 * A cheap fingerprint of the source and manifest files (count, total size, newest modification time) for change
	 * detection; no file is read. Differs whenever a file is added, removed, resized or saved.
	 */
	public String signature(Path root) {
		long[] sig = new long[3];
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					return dir != root && SKIP_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE
							: FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					String name = file.getFileName().toString();
					String rel = root.relativize(file).toString().replace('\\', '/');
					if (MANIFESTS.contains(name) || isSource(name, rel)) {
						sig[0]++;
						sig[1] += attrs.size();
						sig[2] = Math.max(sig[2], attrs.lastModifiedTime().toMillis());
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return sig[0] + ":" + sig[1] + ":" + sig[2];
	}

	/** Dependency rules, matched once per rule: the first manifest line that mentions it is the evidence. */
	private List<External> externals(Path root, List<Path> manifests) {
		Map<String, External> found = new LinkedHashMap<>();
		for (Path manifest : manifests) {
			List<String> lines;
			try {
				if (Files.size(manifest) > 200_000) {
					continue;
				}
				lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
			}
			catch (IOException | UncheckedIOException e) {
				continue;
			}
			String rel = root.relativize(manifest).toString().replace('\\', '/');
			for (int i = 0; i < lines.size(); i++) {
				String lower = lines.get(i).toLowerCase(Locale.ROOT);
				for (Rule rule : rules) {
					if (!found.containsKey(rule.name()) && rule.match().stream().anyMatch(lower::contains)) {
						found.put(rule.name(), new External(rule.name(), rule.layer(), rule.responsibility(),
								rule.imports(), rel + ":" + (i + 1)));
					}
				}
			}
		}
		return List.copyOf(found.values());
	}

	private static boolean isSource(String name, String rel) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || !SOURCE_EXT.contains(name.substring(dot + 1))) {
			return false;
		}
		// tests and generated bundles say nothing about the architecture
		String path = "/" + rel;
		return !(path.contains("/src/test/") || path.contains("/__tests__/") || name.contains(".test.")
				|| name.contains(".spec.") || name.endsWith(".d.ts") || name.contains(".min."));
	}

	private static String sha256(Path file) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Rule> loadRules() {
		try (InputStream in = CodeInventoryService.class.getResourceAsStream("/code-rules.yml")) {
			List<Map<String, Object>> raw = new Yaml().load(in);
			List<Rule> rules = new ArrayList<>();
			for (Map<String, Object> r : raw) {
				rules.add(new Rule((String) r.get("name"), (String) r.get("layer"),
						(String) r.getOrDefault("responsibility", ""),
						((List<Object>) r.get("match")).stream().map(o -> o.toString().toLowerCase(Locale.ROOT)).toList(),
						((List<Object>) r.getOrDefault("imports", List.of())).stream().map(Object::toString).toList()));
			}
			return rules;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}

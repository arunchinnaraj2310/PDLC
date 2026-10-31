package com.pdlc.assistant.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pdlc.assistant.exception.DiagramException;
import com.pdlc.assistant.model.CodeFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs {@code tools/extract/extract.mjs} (Node + tree-sitter) on the given files of a source folder and returns
 * the facts it found. Same shape as {@link ArchifyRunner}: a subprocess in a throwaway temp dir with a timeout.
 */
@Service
public class CodeExtractorRunner {

	/** Facts per file (path and hash not yet set) and the per-file errors the extractor reported. */
	public record Extraction(Map<String, CodeFile> files, List<String> errors) {
	}

	private final Path cli;

	private final String node;

	private final long timeoutSeconds;

	private final JsonMapper json;

	public CodeExtractorRunner(@Value("${pdlc.extract.cli}") String cli, @Value("${pdlc.archify.node}") String node,
			@Value("${pdlc.extract.timeout-seconds}") long timeoutSeconds, JsonMapper json) {
		this.cli = Path.of(cli).toAbsolutePath().normalize();
		this.node = node;
		this.timeoutSeconds = timeoutSeconds;
		this.json = json;
	}

	public Extraction extract(Path root, List<String> files) {
		Path dir = null;
		try {
			dir = Files.createTempDirectory("pdlc-extract-");
			Path list = dir.resolve("files.json");
			Path out = dir.resolve("facts.json");
			Path log = dir.resolve("log.txt");
			Files.writeString(list, json.writeValueAsString(files), StandardCharsets.UTF_8);

			ProcessBuilder pb = new ProcessBuilder(node, cli.toString(), root.toString(), list.toString(),
					out.toString());
			pb.directory(dir.toFile());
			pb.redirectOutput(log.toFile());
			pb.redirectErrorStream(true);
			Process process = pb.start();
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new DiagramException("Code extraction timed out after " + timeoutSeconds + "s");
			}
			if (process.exitValue() != 0 || !Files.isRegularFile(out)) {
				throw new DiagramException("Code extraction failed: " + Files.readString(log, StandardCharsets.UTF_8).strip());
			}
			JsonNode result = json.readTree(Files.readString(out, StandardCharsets.UTF_8));
			Map<String, CodeFile> parsed = new LinkedHashMap<>();
			result.path("files").properties().forEach(e -> parsed.put(e.getKey(), json.treeToValue(e.getValue(), CodeFile.class)));
			List<String> errors = result.path("errors")
				.valueStream()
				.map(e -> e.path("file").asString() + ": " + e.path("error").asString())
				.toList();
			return new Extraction(parsed, errors);
		}
		catch (IOException e) {
			throw new DiagramException("Could not run the extractor (is Node installed, and did you run npm install in "
					+ "tools/extract?): " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DiagramException("Interrupted while extracting code facts", e);
		}
		finally {
			deleteQuietly(dir);
		}
	}

	private static void deleteQuietly(Path dir) {
		if (dir == null) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		catch (IOException e) {
			// temp files only
		}
	}

}

package com.pdlc.assistant.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pdlc.assistant.exception.DiagramException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the vendored Archify CLI as a Node subprocess in a throwaway temp dir. {@code deliver} validates the IR
 * (schema, layout, label and route checks) and only then writes the HTML, so a bad IR never produces a diagram.
 */
@Service
public class ArchifyRunner {

	private final Path cli;

	private final String node;

	private final long timeoutSeconds;

	private final JsonMapper json;

	public ArchifyRunner(@Value("${pdlc.archify.cli}") String cli, @Value("${pdlc.archify.node}") String node,
			@Value("${pdlc.archify.timeout-seconds}") long timeoutSeconds, JsonMapper json) {
		this.cli = Path.of(cli).toAbsolutePath().normalize();
		this.node = node;
		this.timeoutSeconds = timeoutSeconds;
		this.json = json;
	}

	/** Validates and renders the Architecture IR; returns the self-contained HTML. */
	public String render(String irJson) {
		Path dir = null;
		try {
			dir = Files.createTempDirectory("pdlc-archify-");
			Path ir = dir.resolve("architecture.ir.json");
			Path html = dir.resolve("architecture.html");
			Path stdout = dir.resolve("stdout.json");
			Files.writeString(ir, irJson, StandardCharsets.UTF_8);

			ProcessBuilder pb = new ProcessBuilder(node, cli.toString(), "deliver", "architecture", ir.toString(),
					html.toString(), "--json");
			pb.directory(dir.toFile());
			pb.environment().put("ARCHIFY_UPDATE_CHECK_DISABLED", "1");
			pb.redirectOutput(stdout.toFile());
			pb.redirectErrorStream(true);
			Process process = pb.start();
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new DiagramException("Archify timed out after " + timeoutSeconds + "s");
			}
			if (process.exitValue() != 0 || !Files.isRegularFile(html)) {
				throw new DiagramException("Archify rejected the diagram: " + errorMessage(stdout));
			}
			return Files.readString(html, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new DiagramException("Could not run Archify (is Node installed and pdlc.archify.cli correct?): "
					+ e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DiagramException("Interrupted while rendering the diagram", e);
		}
		finally {
			deleteQuietly(dir);
		}
	}

	private String errorMessage(Path stdout) {
		try {
			String text = Files.readString(stdout, StandardCharsets.UTF_8);
			JsonNode error = json.readTree(text).path("error");
			return error.isString() ? error.asString() : text.strip();
		}
		catch (RuntimeException | IOException e) {
			return "(no details)";
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
			// temp files only; the OS cleans them up eventually
		}
	}

}

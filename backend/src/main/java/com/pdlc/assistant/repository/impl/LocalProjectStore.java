package com.pdlc.assistant.repository.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.pdlc.assistant.repository.ProjectStore;

@Component
@ConditionalOnProperty(name = "pdlc.storage.mode", havingValue = "local", matchIfMissing = true)
public class LocalProjectStore implements ProjectStore {

	private final Path root;

	public LocalProjectStore(@Value("${pdlc.storage.root}") String root) {
		this.root = Path.of(root).toAbsolutePath().normalize();
	}

	@Override
	public boolean exists(String projectId, String path) {
		return Files.isRegularFile(resolve(projectId, path));
	}

	@Override
	public String read(String projectId, String path) {
		try {
			return Files.readString(resolve(projectId, path), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void write(String projectId, String path, String content) {
		Path file = resolve(projectId, path);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void delete(String projectId, String path) {
		try {
			Files.deleteIfExists(resolve(projectId, path));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public List<String> list(String projectId, String prefix) {
		Path base = resolve(projectId, "");
		if (!Files.isDirectory(base)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(base)) {
			return files.filter(Files::isRegularFile)
				.map(p -> base.relativize(p).toString().replace('\\', '/'))
				.filter(p -> p.startsWith(prefix))
				.sorted()
				.toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public List<String> listProjects() {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> dirs = Files.list(root)) {
			return dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public Optional<Path> localPath(String projectId) {
		return Optional.of(resolve(projectId, ""));
	}

	private Path resolve(String projectId, String path) {
		Path project = root.resolve(projectId).normalize();
		Path file = project.resolve(path).normalize();
		if (!project.startsWith(root) || !file.startsWith(project)) {
			throw new IllegalArgumentException("Path escapes the project folder: " + path);
		}
		return file;
	}

}

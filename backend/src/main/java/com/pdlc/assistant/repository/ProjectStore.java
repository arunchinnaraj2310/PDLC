package com.pdlc.assistant.repository;

import java.util.List;

/**
 * Where project files live. Paths are project-relative with forward slashes
 * (e.g. {@code docs/11-building-blocks.md}). Only a local-folder implementation
 * exists for now; an S3 one can be added behind the same interface.
 */
public interface ProjectStore {

	boolean exists(String projectId, String path);

	String read(String projectId, String path);

	void write(String projectId, String path, String content);

	void delete(String projectId, String path);

	/** Project-relative paths of all files whose path starts with {@code prefix}. */
	List<String> list(String projectId, String prefix);

	List<String> listProjects();

}

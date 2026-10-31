package com.pdlc.assistant.model;

import java.util.Map;

/**
 * What the last sync learned about a source folder, kept in {@code .pdlc/facts.json}. It is a cache, not the model:
 * files whose hash is unchanged are not parsed again.
 */
public record CodeFacts(String source, Map<String, CodeFile> files) {

	public CodeFacts {
		files = files == null ? Map.of() : files;
	}

}

package com.pdlc.assistant.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * One stable-ID entry in a project md file:
 * <pre>
 * - CMP-003: Todo API
 *   - layer: backend
 *   - satisfies: REQ-001, REQ-002
 * </pre>
 */
public record MdEntry(String id, String text, Map<String, String> attrs) {

	/** Comma-separated attribute as a list; empty if absent. */
	public List<String> list(String key) {
		String value = attrs.get(key);
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	List<String> render() {
		List<String> lines = new ArrayList<>();
		lines.add("- " + id + ": " + text);
		attrs.forEach((k, v) -> lines.add("  - " + k + ": " + v));
		return lines;
	}

}

package com.pdlc.assistant.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What code generation wrote: for each file under {@code implementation/} the component it was generated for and
 * its hash at the time. The hash tells a regenerate whether you changed the file; the component lets a code sync
 * put the file back into the same building block of the design.
 */
@Component
public class GenerationRecord {

	public record Entry(String component, String hash) {
	}

	private static final Logger log = LoggerFactory.getLogger(GenerationRecord.class);

	private static final String FILE = ".pdlc/generated.json";

	private final ProjectService projects;

	private final JsonMapper json;

	public GenerationRecord(ProjectService projects, JsonMapper json) {
		this.projects = projects;
		this.json = json;
	}

	public Map<String, Entry> load(String projectId) {
		String raw = projects.readIfExists(projectId, FILE);
		if (raw == null) {
			return new LinkedHashMap<>();
		}
		try {
			return new LinkedHashMap<>(json.readValue(raw, new TypeReference<Map<String, Entry>>() {
			}));
		}
		catch (RuntimeException e) {
			log.warn("Ignoring unreadable generation record of {}: {}", projectId, e.getMessage());
			return new LinkedHashMap<>();
		}
	}

	public void save(String projectId, Map<String, Entry> record) {
		projects.writeInternal(projectId, FILE, json.writeValueAsString(record));
	}

	/** Path (relative to {@code implementation/}) to the CMP id it was generated for; shared files are left out. */
	public Map<String, String> componentMap(String projectId) {
		Map<String, String> map = new LinkedHashMap<>();
		load(projectId).forEach((path, e) -> {
			if (e.component() != null && !e.component().isBlank()) {
				map.put(path, e.component());
			}
		});
		return map;
	}

}

package com.pdlc.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.pdlc.assistant.exception.DiagramException;
import com.pdlc.assistant.mapper.ArchitectureIrMapper;
import com.pdlc.assistant.model.MdEntry;
import com.pdlc.assistant.repository.ProjectStore;

import tools.jackson.databind.json.JsonMapper;

/** md model to diagram: map CMP entries to Archify IR, render through Archify, keep the results in the project. */
@Service
public class DiagramService {

	static final String IR_FILE = "diagrams/architecture.ir.json";

	private static final int MAX_REROUTES = 3;

	private static final Pattern CONNECTION_ID = Pattern.compile("id \"([^\"]+-to-[^\"]+)\"");

	static final String HTML_FILE = "diagrams/architecture.html";

	private final ProjectService projects;

	private final ArchitectureIrMapper mapper;

	private final ArchifyRunner archify;

	private final ProjectStore store;

	private final JsonMapper json;

	public DiagramService(ProjectService projects, ArchitectureIrMapper mapper, ArchifyRunner archify,
			ProjectStore store, JsonMapper json) {
		this.projects = projects;
		this.mapper = mapper;
		this.archify = archify;
		this.store = store;
		this.json = json;
	}

	/** Renders the current building blocks; the IR and HTML are saved under diagrams/ (derived files, never hand-edited). */
	public String render(String projectId) {
		Rendered rendered = renderResilient(projectId, projects.entries(projectId, ProjectService.CMP_FILE));
		store.write(projectId, IR_FILE, rendered.ir());
		store.write(projectId, HTML_FILE, rendered.html());
		return rendered.html();
	}

	/**
	 * Dry run for proposed components: throws DiagramException if Archify would reject them. Writes nothing.
	 * Returns the connections that could not be drawn (empty normally), as ids like CMP-002-to-CMP-004.
	 */
	public List<String> checkRenders(String projectId, List<MdEntry> components) {
		return renderResilient(projectId, components).omitted();
	}

	private record Rendered(String ir, String html, List<String> omitted) {
	}

	/**
	 * Archify refuses a diagram when an edge cannot be routed without crossing an unrelated box, which happens with
	 * many components. Rather than show nothing, the offending connections are left out (they stay in the md as
	 * links), a few rounds at most, and the title says how many are not drawn. Other rejections still throw.
	 */
	private Rendered renderResilient(String projectId, List<MdEntry> components) {
		Map<String, Object> ir = mapper.toIr(projectId + " architecture", components);
		List<String> omitted = new ArrayList<>();
		for (int attempt = 0; ; attempt++) {
			String irJson = json.writerWithDefaultPrettyPrinter().writeValueAsString(ir);
			try {
				return new Rendered(irJson, archify.render(irJson), omitted);
			}
			catch (DiagramException e) {
				List<String> offending = offendingConnections(e.getMessage());
				if (attempt >= MAX_REROUTES || offending.isEmpty()) {
					throw e;
				}
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> connections = new ArrayList<>((List<Map<String, Object>>) ir.get("connections"));
				connections.removeIf(c -> offending.contains(String.valueOf(c.get("id"))));
				omitted.addAll(offending);
				ir.put("connections", connections);
				ir.put("meta", Map.of("title", projectId + " architecture (" + omitted.size() + " links not drawn)",
						"output", "architecture.html"));
			}
		}
	}

	private static List<String> offendingConnections(String message) {
		List<String> ids = new ArrayList<>();
		if (message != null && message.contains("[clean-flow/edge-through-node]")) {
			Matcher m = CONNECTION_ID.matcher(message);
			while (m.find()) {
				if (!ids.contains(m.group(1))) {
					ids.add(m.group(1));
				}
			}
		}
		return ids;
	}

}

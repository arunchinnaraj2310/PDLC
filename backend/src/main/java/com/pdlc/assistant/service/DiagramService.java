package com.pdlc.assistant.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.pdlc.assistant.mapper.ArchitectureIrMapper;
import com.pdlc.assistant.model.MdEntry;
import com.pdlc.assistant.repository.ProjectStore;

import tools.jackson.databind.json.JsonMapper;

/** md model to diagram: map CMP entries to Archify IR, render through Archify, keep the results in the project. */
@Service
public class DiagramService {

	static final String IR_FILE = "diagrams/architecture.ir.json";

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
		String irJson = toIrJson(projectId, projects.entries(projectId, ProjectService.CMP_FILE));
		String html = archify.render(irJson);
		store.write(projectId, IR_FILE, irJson);
		store.write(projectId, HTML_FILE, html);
		return html;
	}

	/** Dry run for proposed components: throws DiagramException if Archify would reject them. Writes nothing. */
	public void checkRenders(String projectId, List<MdEntry> components) {
		archify.render(toIrJson(projectId, components));
	}

	private String toIrJson(String projectId, List<MdEntry> components) {
		return json.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.toIr(projectId + " architecture", components));
	}

}

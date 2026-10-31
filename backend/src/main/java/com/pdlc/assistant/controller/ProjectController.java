package com.pdlc.assistant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pdlc.assistant.dto.PatchRequest;
import com.pdlc.assistant.dto.PatchResult;
import com.pdlc.assistant.dto.PatchSetRequest;
import com.pdlc.assistant.model.MdEntry;
import com.pdlc.assistant.service.CodeSyncService;
import com.pdlc.assistant.service.ProjectService;

@RestController
public class ProjectController {

	private final ProjectService projects;

	private final CodeSyncService codeSync;

	public ProjectController(ProjectService projects, CodeSyncService codeSync) {
		this.codeSync = codeSync;
		this.projects = projects;
	}

	@GetMapping("/api/projects")
	public List<String> list() {
		return projects.listProjects();
	}

	@PostMapping("/api/projects/{id}")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, String> create(@PathVariable String id) {
		projects.create(id);
		return Map.of("id", id);
	}

	/** What the last code sync did; {@code version} goes up whenever the design was changed from the code. */
	@GetMapping("/api/projects/{id}/code/status")
	public CodeSyncService.AutoSync codeStatus(@PathVariable String id) {
		return codeSync.autoStatus(id);
	}

	@GetMapping("/api/projects/{id}/files")
	public List<String> files(@PathVariable String id) {
		return projects.listFiles(id);
	}

	@GetMapping(value = "/api/projects/{id}/file", produces = MediaType.TEXT_PLAIN_VALUE)
	public String file(@PathVariable String id, @RequestParam String path) {
		return projects.readFile(id, path);
	}

	@GetMapping("/api/projects/{id}/entries")
	public List<MdEntry> entries(@PathVariable String id, @RequestParam String file) {
		return projects.entries(id, file);
	}

	/** With dryRun=true nothing is written; the response carries the patched content for a diff card. */
	@PostMapping("/api/projects/{id}/patch")
	public PatchResult patch(@PathVariable String id, @RequestBody PatchRequest req,
			@RequestParam(defaultValue = "false") boolean dryRun) {
		return dryRun ? projects.preview(id, req.file(), req.ops())
				: projects.apply(id, req.file(), req.reason(), req.ops());
	}

	/** Several files at once, validated together (e.g. new requirements plus the components that satisfy them). */
	@PostMapping("/api/projects/{id}/patch-set")
	public Map<String, PatchResult> patchSet(@PathVariable String id, @RequestBody PatchSetRequest req,
			@RequestParam(defaultValue = "false") boolean dryRun) {
		return dryRun ? projects.previewAll(id, req.patches()) : projects.applyAll(id, req.reason(), req.patches());
	}

	@PostMapping("/api/projects/{id}/undo")
	public Map<String, String> undo(@PathVariable String id, @RequestParam String file) {
		projects.undo(id, file);
		return Map.of("restored", file);
	}

}

package com.pdlc.assistant.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.pdlc.assistant.dto.ImportProposal;
import com.pdlc.assistant.service.RequirementsImportService;

@RestController
public class ImportController {

	private final RequirementsImportService imports;

	public ImportController(RequirementsImportService imports) {
		this.imports = imports;
	}

	/**
	 * Body is the raw requirements.md (text/plain or text/markdown). Returns a proposal only; apply it by posting
	 * its {@code patches} to {@code /patch-set}.
	 */
	@PostMapping(value = "/api/projects/{id}/import/requirements", consumes = { "text/plain", "text/markdown" })
	public ImportProposal importRequirements(@PathVariable String id, @RequestBody String markdown) {
		return imports.propose(id, markdown);
	}

}

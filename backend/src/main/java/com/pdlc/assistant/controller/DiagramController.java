package com.pdlc.assistant.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.pdlc.assistant.service.DiagramService;

@RestController
public class DiagramController {

	private final DiagramService diagrams;

	public DiagramController(DiagramService diagrams) {
		this.diagrams = diagrams;
	}

	/** Always re-renders from the md model, so the diagram can never be stale. Embed it in an iframe. */
	@GetMapping(value = "/api/projects/{id}/diagram", produces = MediaType.TEXT_HTML_VALUE)
	public String diagram(@PathVariable String id) {
		return diagrams.render(id);
	}

}

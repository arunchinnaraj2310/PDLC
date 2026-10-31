package com.pdlc.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.pdlc.assistant.dto.FileDiff;
import com.pdlc.assistant.dto.FilePatch;
import com.pdlc.assistant.dto.ImportProposal;
import com.pdlc.assistant.dto.PatchResult;
import com.pdlc.assistant.exception.DiagramException;
import com.pdlc.assistant.model.MdDocument;

/**
 * Turns validated patch results into the proposal shown to the admin: per-file before/after for the diff card,
 * plus a dry-run Archify render of the proposed components so a change the diagram cannot show is flagged
 * before Apply. Shared by every flow that proposes changes (requirements import, chat design changes).
 */
@Service
public class ProposalBuilder {

	private final ProjectService projects;

	private final DiagramService diagrams;

	public ProposalBuilder(ProjectService projects, DiagramService diagrams) {
		this.projects = projects;
		this.diagrams = diagrams;
	}

	/** {@code results} come from {@link ProjectService#previewAll}; {@code warnings} is extended in place. */
	public ImportProposal build(String projectId, String summary, List<FilePatch> patches, List<String> warnings,
			Map<String, PatchResult> results) {
		List<FileDiff> files = new ArrayList<>();
		results.forEach((file, r) -> files
			.add(new FileDiff(file, projects.readFile(projectId, file), r.content(), r.version())));
		PatchResult components = results.get(ProjectService.CMP_FILE);
		if (components != null) {
			try {
				diagrams.checkRenders(projectId, MdDocument.parse(components.content()).entries());
			}
			catch (DiagramException | IllegalArgumentException e) {
				warnings.add("The proposed components would not render: " + e.getMessage());
			}
		}
		return new ImportProposal(summary, patches, files, warnings);
	}

}

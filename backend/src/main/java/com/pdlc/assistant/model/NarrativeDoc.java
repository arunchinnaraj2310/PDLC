package com.pdlc.assistant.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The prose documents the chat interview produces, before requirements and design exist. They are made of '## '
 * sections: the model returns only the sections that change and {@link MdSections} merges them in.
 */
public enum NarrativeDoc {

	PRODUCT_INTENT("docs/01-product-intent.md", "DOC-01", "Product Intent",
			List.of("Vision", "Problem", "Target users", "Goals and success metrics", "Scope",
					"Constraints and assumptions", "Open questions"),
			"Sections (use exactly these headings): Vision and Problem are one paragraph each (op set); Target users, "
					+ "Goals and success metrics, Constraints and assumptions and Open questions are bullet lists; Scope "
					+ "bullets start with 'In scope:' or 'Out of scope:'."),
	PERSONAS("docs/02-personas.md", "DOC-02", "Personas", List.of(),
			"One section per persona, headed by the persona's name, with bullets 'Role: ...', 'Goals: ...' and "
					+ "'Pain points: ...'."),
	USER_STORIES("docs/03-user-stories.md", "DOC-03", "User Stories", List.of(),
			"One section per persona, headed by the persona's name, one story per bullet. Acceptance criteria, when "
					+ "given, go at the end of the same bullet."),
	TECH_DECISIONS("docs/05-technical-decisions.md", "DOC-05", "Technical Decisions",
			List.of("Architecture style", "Frontend", "Backend", "Database", "Caching", "Authentication",
					"Cloud and deployment"),
			"Sections (use exactly these headings), each a list of bullets of the form 'Label: value', one decision "
					+ "per bullet. Architecture style: 'Style: ...', 'API style: ...'. Frontend: 'Language: ...', "
					+ "'Framework: ...', 'Build tool: ...', 'Styling: ...'. Backend: 'Language: ...', 'Framework: ...', "
					+ "'Build tool: ...'. Database: 'Engine: ...', 'Schema management: ...'. Caching: 'Mechanism: ...', "
					+ "'Cached data: ...'. Authentication: 'Method: ...', 'Provider: ...'. Cloud and deployment: "
					+ "'Provider: ...', 'Runtime: ...', 'CI/CD: ...', 'Environments: ...'. Versions are part of the value "
					+ "when the admin gave them. A section the admin says is not needed gets one bullet such as "
					+ "'Not needed: no caching'.");

	private final String path;

	private final String docId;

	private final String title;

	private final List<String> sections;

	private final String guidance;

	NarrativeDoc(String path, String docId, String title, List<String> sections, String guidance) {
		this.path = path;
		this.docId = docId;
		this.title = title;
		this.sections = sections;
		this.guidance = guidance;
	}

	public String path() {
		return path;
	}

	public String docId() {
		return docId;
	}

	public String title() {
		return title;
	}

	/** Canonical section order; empty when the sections are free-form (named after personas). */
	public List<String> sections() {
		return sections;
	}

	/** Structure the model must follow for this document. */
	public String guidance() {
		return guidance;
	}

	/** Content of a document nobody has written yet; {@code status: empty} marks the interview as not started. */
	public String template() {
		return "---\nid: " + docId + "\nversion: 1\nstatus: empty\n---\n# " + title
				+ "\n\n<!-- Filled in from the product interview in the chat. -->\n";
	}

	public static Optional<NarrativeDoc> byPath(String path) {
		return Arrays.stream(values()).filter(d -> d.path.equals(path)).findFirst();
	}

}

package com.pdlc.assistant.model;

/**
 * The ordered steps of the product interview. Each fills one section (or one whole document) and has a plain
 * test for "enough information", which the model applies and the backend double-checks against the document.
 * Completed steps are remembered in the front matter of the document they belong to ({@code done: VISION, ...}).
 */
public enum InterviewStep {

	VISION(NarrativeDoc.PRODUCT_INTENT, "Vision", "Vision",
			"it says what the product is and the outcome it should achieve, in a sentence or two"),
	PROBLEM(NarrativeDoc.PRODUCT_INTENT, "Problem", "Problem",
			"it names who has which pain today and what it costs them (a brief consequence is enough)"),
	TARGET_USERS(NarrativeDoc.PRODUCT_INTENT, "Target users", "Target users",
			"at least one specific user group is named with something that sets it apart (role, size, context)"),
	GOALS(NarrativeDoc.PRODUCT_INTENT, "Goals", "Goals and success metrics",
			"there is at least one goal with a measurable success metric (a number, a rate or an observable target)"),
	SCOPE(NarrativeDoc.PRODUCT_INTENT, "Scope", "Scope",
			"at least three in-scope capabilities are listed and at least one out-of-scope item (or the admin said "
					+ "that nothing is excluded)"),
	CONSTRAINTS(NarrativeDoc.PRODUCT_INTENT, "Constraints", "Constraints and assumptions",
			"platform, timeline, budget, compliance and technology preferences were asked about and answered, or the "
					+ "admin said there are none (record that as one bullet)"),
	PERSONAS(NarrativeDoc.PERSONAS, "Personas", null,
			"at least one persona exists with a role, goals and pain points"),
	USER_STORIES(NarrativeDoc.USER_STORIES, "User stories", null,
			"there are stories that together cover every in-scope capability, each for a named persona");

	private final NarrativeDoc doc;

	private final String label;

	private final String section;

	private final String enough;

	InterviewStep(NarrativeDoc doc, String label, String section, String enough) {
		this.doc = doc;
		this.label = label;
		this.section = section;
		this.enough = enough;
	}

	public NarrativeDoc doc() {
		return doc;
	}

	/** Short name for the progress strip. */
	public String label() {
		return label;
	}

	/** The section this step fills; null when the step is a whole document (personas, stories). */
	public String section() {
		return section;
	}

	/** What must be true before the interview moves on. */
	public String enough() {
		return enough;
	}

	/** Whether the document body has anything written for this step; a completed step can never be empty. */
	public boolean hasContent(String docBody) {
		if (docBody == null || docBody.isBlank()) {
			return false;
		}
		MdSections sections = MdSections.parse(docBody);
		if (section != null) {
			return sections.hasBody(section);
		}
		return this == USER_STORIES ? docBody.contains("US-") : !sections.isEmpty();
	}

}

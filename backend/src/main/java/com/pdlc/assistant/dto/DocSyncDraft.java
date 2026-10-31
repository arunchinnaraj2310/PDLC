package com.pdlc.assistant.dto;

import java.util.List;

import com.pdlc.assistant.model.NarrativeDoc;

/**
 * LLM output schema for the interview: small edits to the product documents, one line of text each. The backend
 * validates and merges them, so the model can neither wipe a document nor repeat one.
 */
public class DocSyncDraft {

	/** Empty when the message carried no project information. */
	public List<Edit> edits;

	/** One short sentence on what changed; empty when nothing did. */
	public String changeNote;

	public enum Op {

		/** Replace the whole body of a section (for single-paragraph sections such as Vision and Problem). */
		set,

		/** Append one bullet to a section, creating the section when it does not exist. */
		add,

		/** Delete the bullet that matches {@code text} (its start, or a story id such as US-002). */
		remove,

		/** Delete a whole section, for example a persona that no longer applies. */
		removeSection

	}

	public static class Edit {

		public NarrativeDoc file;

		/** The section heading without hashes: a Product Intent section, or a persona's name. */
		public String section;

		public Op op;

		/** One bullet, or the paragraph for {@code set}. No headings, no markdown list markers, no story ids. */
		public String text;

	}

}

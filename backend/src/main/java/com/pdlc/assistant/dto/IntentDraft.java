package com.pdlc.assistant.dto;

/** LLM output schema for the intent router: one closed value, so the model cannot answer anything else. */
public class IntentDraft {

	public enum Intent {

		/** The user asks to add, remove or modify something in the requirements or architecture. */
		change,

		/** The user states or changes the product itself: vision, problem, target users, goals, scope, user stories. */
		product,

		/** Anything else: a question about the project, an explanation, small talk. */
		question

	}

	public Intent intent;

}

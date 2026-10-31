package com.pdlc.assistant.dto;

/** LLM output schema for judging one interview step: is there enough information to move on? */
public class StepCheck {

	public boolean met;

	/** In a few words, what is still needed; empty when {@code met}. */
	public String missing;

}

package com.pdlc.assistant.dto;

/** LLM output schema for code generation step 2: the complete content of one planned file. */
public class GeneratedFile {

	/** The whole file, exactly as it should be saved: no markdown fences, no commentary. */
	public String content;

}

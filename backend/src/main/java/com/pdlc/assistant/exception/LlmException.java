package com.pdlc.assistant.exception;

/** The LLM call failed or kept returning output that does not match the schema. */
public class LlmException extends RuntimeException {

	public LlmException(String message) {
		super(message);
	}

	public LlmException(String message, Throwable cause) {
		super(message, cause);
	}

}

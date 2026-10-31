package com.pdlc.assistant.dto;

/**
 * One LLM call. {@code task} is only a label for the usage log. A null {@code model} or
 * {@code maxCompletionTokens} falls back to the gateway defaults.
 */
public record LlmRequest(String task, String system, String user, String model, Integer maxCompletionTokens) {

	public static LlmRequest of(String task, String system, String user) {
		return new LlmRequest(task, system, user, null, null);
	}

}

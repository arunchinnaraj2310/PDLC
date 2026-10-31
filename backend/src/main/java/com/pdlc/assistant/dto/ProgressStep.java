package com.pdlc.assistant.dto;

/** One step of the product interview for the progress strip; state is "done", "current" or "todo". */
public record ProgressStep(String key, String label, String state) {
}

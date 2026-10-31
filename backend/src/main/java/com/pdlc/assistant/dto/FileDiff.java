package com.pdlc.assistant.dto;

/** Before and after content of one md file, for a diff card. */
public record FileDiff(String file, String before, String after, int version) {
}

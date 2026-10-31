package com.pdlc.assistant.dto;

/** Result of a patch: the new file content and the version it carries. */
public record PatchResult(String content, int version) {
}

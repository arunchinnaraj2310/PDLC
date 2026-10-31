package com.pdlc.assistant.dto;

import java.util.List;

/**
 * What a requirements import or a chat design change would change. Nothing is written yet: {@code summary} is a
 * one-line description, {@code files} is for the diff card, and {@code patches} can be posted back verbatim to
 * {@code /patch-set} to apply it.
 */
public record ImportProposal(String summary, List<FilePatch> patches, List<FileDiff> files, List<String> warnings) {
}

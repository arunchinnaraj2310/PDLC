package com.pdlc.assistant.dto;

import java.util.List;

/** Body of a multi-file patch: applied together or not at all. */
public record PatchSetRequest(String reason, List<FilePatch> patches) {
}

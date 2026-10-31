package com.pdlc.assistant.dto;

import java.util.List;

import com.pdlc.assistant.model.PatchOp;

/** Body of a patch call: which md file, why, and the section-level changes. */
public record PatchRequest(String file, String reason, List<PatchOp> ops) {
}

package com.pdlc.assistant.dto;

import java.util.List;

import com.pdlc.assistant.model.PatchOp;

/** Section-level changes to one md file. */
public record FilePatch(String file, List<PatchOp> ops) {
}

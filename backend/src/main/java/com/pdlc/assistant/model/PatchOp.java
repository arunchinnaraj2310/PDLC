package com.pdlc.assistant.model;

import java.util.Map;

/**
 * Section-level change to one entry. {@code op} is add | update | remove.
 * On update, a null {@code text} keeps the old text and an attr with a blank value is removed.
 */
public record PatchOp(String op, String id, String text, Map<String, String> attrs) {

	public PatchOp {
		attrs = attrs == null ? Map.of() : attrs;
	}

}

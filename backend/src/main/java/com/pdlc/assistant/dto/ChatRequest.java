package com.pdlc.assistant.dto;

import java.util.List;

/**
 * A chat message plus the recent conversation (the server keeps no chat state). {@code mode} is
 * "requirements" to force the requirements-import flow; anything else lets the server decide.
 */
public record ChatRequest(String message, List<ChatTurn> history, String mode) {
}

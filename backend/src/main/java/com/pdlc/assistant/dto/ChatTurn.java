package com.pdlc.assistant.dto;

/** One earlier message of the conversation; {@code role} is "user" or "assistant". */
public record ChatTurn(String role, String content) {
}

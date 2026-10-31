package com.pdlc.assistant.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, String> badRequest(IllegalArgumentException e) {
		return Map.of("error", e.getMessage());
	}

	@ExceptionHandler(DiagramException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
	public Map<String, String> diagramFailure(DiagramException e) {
		return Map.of("error", e.getMessage());
	}

	@ExceptionHandler(LlmException.class)
	@ResponseStatus(HttpStatus.BAD_GATEWAY)
	public Map<String, String> llmFailure(LlmException e) {
		return Map.of("error", e.getMessage());
	}

}

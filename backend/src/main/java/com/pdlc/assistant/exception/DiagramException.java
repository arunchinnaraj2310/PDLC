package com.pdlc.assistant.exception;

/** Archify could not validate or render the diagram. */
public class DiagramException extends RuntimeException {

	public DiagramException(String message) {
		super(message);
	}

	public DiagramException(String message, Throwable cause) {
		super(message, cause);
	}

}

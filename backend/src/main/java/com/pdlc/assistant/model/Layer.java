package com.pdlc.assistant.model;

/**
 * Architecture layer of a component. Constants are in diagram row order (top to bottom) and are valid
 * Archify component types. Also used as an enum in LLM output schemas so the model can only pick valid values.
 */
public enum Layer {

	frontend, backend, security, messagebus, database, cloud, external

}

package com.pdlc.assistant.dto;

import java.util.List;

/**
 * LLM output schema for a chat design change. Existing entries are referenced by their real ids (REQ-001,
 * CMP-002). New entries get a short key (n1, n2, ...) in {@code id}; the backend assigns the real ids and
 * resolves those keys wherever they are used. Every field is always present (the schema is strict): an empty
 * string or {@link LayerChoice#keep} means "leave unchanged" on update, and empty lists mean nothing to add or remove.
 */
public class DesignChangeDraft {

	public enum Action {

		add, update, remove

	}

	/** Same values as {@code model.Layer}, plus {@code keep}; an enum so the model cannot invent a layer. */
	public enum LayerChoice {

		keep, frontend, backend, security, messagebus, database, cloud, external

	}

	/** One-sentence description of the whole change. */
	public String summary;

	public List<ReqChange> requirements;

	public List<ComponentChange> components;

	public static class ReqChange {

		public Action action;

		/** Existing REQ id for update/remove; a new key (n1..) for add. */
		public String id;

		/** Requirement sentence (add/update); empty for remove. */
		public String text;

	}

	public static class ComponentChange {

		public Action action;

		/** Existing CMP id for update/remove; a new key (n1..) for add. */
		public String id;

		/** Component name; empty on update to keep it. */
		public String name;

		public LayerChoice layer;

		/** One short sentence; empty on update to keep it. */
		public String responsibility;

		/** Requirement ids or new keys this component now implements. */
		public List<String> addSatisfies;

		public List<String> removeSatisfies;

		/** Component ids or new keys this component now calls or depends on (caller to callee). */
		public List<String> addLinks;

		public List<String> removeLinks;

	}

}

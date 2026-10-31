package com.pdlc.assistant.dto;

import java.util.List;

import com.pdlc.assistant.model.Layer;

/** LLM output schema for naming code clusters: one label per cluster key it was given. */
public class ClusterLabels {

	public List<Label> labels;

	public static class Label {

		/** Exactly the cluster key from the input. */
		public String key;

		/** At most 3 words and 25 characters. */
		public String name;

		public Layer layer;

		/** One short sentence, derived only from the facts. */
		public String responsibility;

		/** Ids (REQ-001, ...) of the listed requirements this cluster clearly implements; empty when unsure. */
		public List<String> satisfies;

	}

}

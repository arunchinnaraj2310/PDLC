package com.pdlc.assistant.dto;

import java.util.List;

import com.pdlc.assistant.model.Layer;

/**
 * LLM output schema for requirements import. The model uses its own short keys (r1.., c1..) and never picks
 * final REQ/CMP ids; the backend assigns those so ids can never clash with existing entries.
 */
public class RequirementsDraft {

	public List<Req> requirements;

	public List<Component> components;

	public static class Req {

		public String key;

		public String text;

	}

	public static class Component {

		public String key;

		public String name;

		public Layer layer;

		public String responsibility;

		/** Keys of the requirements this component directly implements. */
		public List<String> satisfies;

		/** Keys of the components this one calls or depends on. */
		public List<String> links;

	}

}

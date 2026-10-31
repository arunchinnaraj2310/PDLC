package com.pdlc.assistant.dto;

import java.util.List;

/** LLM output schema for code generation step 1: the files of the application, before any is written. */
public class CodePlan {

	public List<PlannedFile> files;

	public static class PlannedFile {

		/** Relative to the application root, forward slashes, no leading slash. */
		public String path;

		/** What the file does, in one sentence. */
		public String purpose;

		/** The CMP id this file implements (CMP-001); empty for build, config and documentation files. */
		public String component;

		/** The public names other files may rely on (classes, functions, endpoints, with signatures); may be empty. */
		public String api;

	}

}

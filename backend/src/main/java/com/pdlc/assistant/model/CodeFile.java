package com.pdlc.assistant.model;

import java.util.List;

/**
 * Facts the extractor found in one source file, each with a 1-based line so the design can cite evidence.
 * {@code path} and {@code hash} are filled in by the backend (the extractor keys files by path and knows no hash).
 */
public record CodeFile(String path, String hash, String lang, String pkg, boolean partial, boolean hasMain,
		List<Import> imports, List<TypeInfo> types, List<Route> routes, List<Outbound> outbound,
		List<Entity> entities, List<String> exports, List<String> usedTypes) {

	public CodeFile {
		usedTypes = usedTypes == null ? List.of() : usedTypes;
		imports = imports == null ? List.of() : imports;
		types = types == null ? List.of() : types;
		routes = routes == null ? List.of() : routes;
		outbound = outbound == null ? List.of() : outbound;
		entities = entities == null ? List.of() : entities;
		exports = exports == null ? List.of() : exports;
	}

	public CodeFile with(String newPath, String newHash) {
		return new CodeFile(newPath, newHash, lang, pkg, partial, hasMain, imports, types, routes, outbound,
				entities, exports, usedTypes);
	}

	/** {@code isStatic} is only set for Java imports. */
	public record Import(String spec, int line, Boolean isStatic) {
	}

	public record TypeInfo(String name, String kind, List<String> annotations, String superclass,
			List<String> interfaces, int line) {

		public TypeInfo {
			annotations = annotations == null ? List.of() : annotations;
			interfaces = interfaces == null ? List.of() : interfaces;
		}

	}

	public record Route(String method, String path, int line, String handler) {
	}

	public record Outbound(String kind, String method, String url, int line) {
	}

	public record Entity(String name, String table, int line) {
	}

}

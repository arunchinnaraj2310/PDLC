package com.pdlc.assistant.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pdlc.assistant.dto.ClusterLabels;
import com.pdlc.assistant.dto.LlmRequest;
import com.pdlc.assistant.model.CodeFile;
import com.pdlc.assistant.model.MdEntry;
import com.pdlc.assistant.service.CodeGraphService.Cluster;
import com.pdlc.assistant.service.CodeGraphService.Graph;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 7.4: names code clusters. The model sees only a few lines of facts per cluster (type and route names,
 * dependencies), never source. Results are cached in {@code .pdlc/labels.json} by a hash of those facts (plus the
 * requirement list, because the answer includes which requirements a cluster satisfies), so an unchanged cluster
 * costs nothing on the next sync.
 */
@Service
public class CodeLabelService {

	/** A cluster's name and role, as cached and as used for new components. */
	public record Label(String name, String layer, String responsibility, List<String> satisfies) {
	}

	private record Cached(String hash, Label label) {
	}

	private static final Logger log = LoggerFactory.getLogger(CodeLabelService.class);

	private static final String CACHE = ".pdlc/labels.json";

	private static final int MAX_COMPLETION_TOKENS = 1800;

	private static final String SYSTEM = """
			You name the building blocks of an existing code base for an architecture diagram. Each cluster is a \
			group of source files, described only by facts found in the code (type names, routes, entities, exports, \
			dependencies). For every cluster key given, return one label with exactly that key:
			name: at most 3 words and 25 characters, a plain product-level name such as 'Order API' or 'Web UI'; no \
			package or folder paths.
			layer: frontend for browser UI code, security for authentication and authorization, otherwise backend.
			responsibility: one short sentence, derived only from the facts; do not invent features.
			satisfies: ids of the listed requirements that this cluster clearly implements; empty when none or unsure.""";

	private final ProjectService projects;

	private final LlmGateway llm;

	private final JsonMapper json;

	public CodeLabelService(ProjectService projects, LlmGateway llm, JsonMapper json) {
		this.projects = projects;
		this.llm = llm;
		this.json = json;
	}

	/** Labels for the given clusters; calls the model only for those that are not cached. */
	public Map<String, Label> label(String projectId, List<Cluster> clusters, Graph graph, Map<String, CodeFile> files,
			List<MdEntry> requirements) {
		String reqText = requirements.stream()
			.limit(40)
			.map(r -> r.id() + ": " + shorten(r.text(), 120))
			.collect(Collectors.joining("\n"));
		Map<String, Cached> cache = loadCache(projectId);
		Map<String, Label> result = new LinkedHashMap<>();
		Map<String, String> facts = new LinkedHashMap<>();
		Map<String, String> hashes = new LinkedHashMap<>();
		for (Cluster cluster : clusters) {
			String text = describe(cluster, graph, files);
			String hash = sha(text + "\n" + reqText);
			Cached hit = cache.get(cluster.key());
			if (hit != null && hit.hash().equals(hash)) {
				result.put(cluster.key(), hit.label());
			}
			else {
				facts.put(cluster.key(), text);
				hashes.put(cluster.key(), hash);
			}
		}
		if (!facts.isEmpty()) {
			Set<String> reqIds = requirements.stream().map(MdEntry::id).collect(Collectors.toSet());
			String input = "Clusters:\n" + String.join("\n", facts.values())
					+ (reqText.isEmpty() ? "\n\nRequirements: (none)" : "\n\nRequirements:\n" + reqText);
			ClusterLabels labels = llm.complete(new LlmRequest("label-clusters", SYSTEM, input, null, MAX_COMPLETION_TOKENS),
					ClusterLabels.class);
			Map<String, ClusterLabels.Label> byKey = new LinkedHashMap<>();
			if (labels.labels != null) {
				labels.labels.stream().filter(l -> l.key != null).forEach(l -> byKey.putIfAbsent(l.key, l));
			}
			for (Cluster cluster : clusters) {
				if (!facts.containsKey(cluster.key())) {
					continue;
				}
				Label label = clean(byKey.get(cluster.key()), cluster, reqIds);
				result.put(cluster.key(), label);
				cache.put(cluster.key(), new Cached(hashes.get(cluster.key()), label));
			}
			saveCache(projectId, cache);
			log.info("code labels project={} labelled={} cached={}", projectId, facts.size(), result.size() - facts.size());
		}
		return result;
	}

	/** Keeps the model's answer inside what the diagram and the md validation accept. */
	private static Label clean(ClusterLabels.Label raw, Cluster cluster, Set<String> reqIds) {
		String fallback = prettify(cluster.key());
		String name = raw == null || raw.name == null || raw.name.isBlank() ? fallback : raw.name.strip();
		if (name.length() > 25) {
			name = name.substring(0, 25).strip();
		}
		// the layer comes from the files (language, routes, folders); the model may only promote a cluster to security
		String layer = raw != null && raw.layer != null && raw.layer.name().equals("security") ? "security" : cluster.layer();
		String responsibility = raw == null || raw.responsibility == null || raw.responsibility.isBlank()
				? "Code under " + cluster.key() : raw.responsibility.strip();
		List<String> satisfies = raw == null || raw.satisfies == null ? List.of()
				: raw.satisfies.stream().filter(reqIds::contains).distinct().toList();
		return new Label(name, layer, responsibility, satisfies);
	}

	/** A few lines of facts: what the cluster contains and who it talks to. No source text. */
	static String describe(Cluster cluster, Graph graph, Map<String, CodeFile> files) {
		List<CodeFile> members = cluster.files().stream().map(files::get).toList();
		List<String> types = members.stream()
			.flatMap(f -> f.types().stream())
			.map(t -> t.name() + (t.annotations().isEmpty() ? "" : "(" + t.annotations().get(0) + ")"))
			.limit(10)
			.toList();
		List<String> routes = members.stream()
			.flatMap(f -> f.routes().stream())
			.map(r -> r.method() + " " + r.path())
			.limit(6)
			.toList();
		List<String> entities = members.stream().flatMap(f -> f.entities().stream()).map(CodeFile.Entity::name).limit(6).toList();
		Set<String> exports = new LinkedHashSet<>();
		members.forEach(f -> exports.addAll(f.exports()));
		List<String> uses = graph.externalUsers()
			.entrySet()
			.stream()
			.filter(e -> e.getValue().contains(cluster.key()))
			.map(Map.Entry::getKey)
			.toList();
		List<String> dependsOn = graph.edges().stream().filter(e -> e.from().equals(cluster.key())).map(CodeGraphService.Edge::to).toList();

		StringBuilder out = new StringBuilder("- key: " + cluster.key() + " | files: " + cluster.files().size());
		append(out, "types", types);
		append(out, "routes", routes);
		append(out, "entities", entities);
		append(out, "exports", exports.stream().limit(8).toList());
		append(out, "uses", uses);
		append(out, "depends on", dependsOn);
		if (types.isEmpty() && exports.isEmpty()) {
			append(out, "file names", cluster.files().stream().map(p -> p.substring(p.lastIndexOf('/') + 1)).limit(6).toList());
		}
		return out.toString();
	}

	private static void append(StringBuilder out, String label, List<String> values) {
		if (!values.isEmpty()) {
			out.append(" | ").append(label).append(": ").append(String.join(", ", values));
		}
	}

	private static String prettify(String key) {
		String last = key.substring(key.lastIndexOf('/') + 1).replace('-', ' ').replace('_', ' ');
		return last.isEmpty() ? key : Character.toUpperCase(last.charAt(0)) + last.substring(1);
	}

	private static String shorten(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "…";
	}

	private Map<String, Cached> loadCache(String projectId) {
		String raw = projects.readIfExists(projectId, CACHE);
		if (raw == null) {
			return new LinkedHashMap<>();
		}
		try {
			return new LinkedHashMap<>(json.readValue(raw, new TypeReference<Map<String, Cached>>() {
			}));
		}
		catch (RuntimeException e) {
			log.warn("Ignoring unreadable label cache of {}: {}", projectId, e.getMessage());
			return new LinkedHashMap<>();
		}
	}

	private void saveCache(String projectId, Map<String, Cached> cache) {
		projects.writeInternal(projectId, CACHE, json.writeValueAsString(cache));
	}

	private static String sha(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}

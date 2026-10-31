package com.pdlc.assistant.mapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.pdlc.assistant.model.Layer;
import com.pdlc.assistant.model.MdEntry;

/**
 * Deterministic md model to Archify Architecture IR. Rows follow the layer order below (empty layers are
 * skipped), columns follow the order of the entries, and every {@code links} reference becomes an unlabeled
 * connection (Archify rejects labels that overlap component boxes). Routes are left to Archify: forcing
 * fromSide/toSide made straight edges cross components that share a column. No LLM involved.
 */
@Component
public class ArchitectureIrMapper {

	/** Top-to-bottom row order; each value is also a valid Archify component type. */
	static final List<String> LAYER_ORDER = Arrays.stream(Layer.values()).map(Layer::name).toList();

	/** Box width in px (Archify's default 120 is too narrow for typical names) and the label length that fits it. */
	private static final int BOX_WIDTH = 180;

	private static final int MAX_LABEL_CHARS = 25;

	/** Most boxes in one row; a wider layer wraps. */
	private static final int MAX_COLS = 5;

	public Map<String, Object> toIr(String title, List<MdEntry> components) {
		if (components.isEmpty()) {
			throw new IllegalArgumentException("No components yet: add CMP entries to docs/11-building-blocks.md");
		}
		Map<String, List<MdEntry>> byLayer = new LinkedHashMap<>();
		for (String layer : LAYER_ORDER) {
			List<MdEntry> inLayer = components.stream().filter(c -> layer.equals(c.attrs().get("layer"))).toList();
			if (!inLayer.isEmpty()) {
				byLayer.put(layer, inLayer);
			}
		}

		Map<String, Integer> depth = depths(components);
		Set<String> ids = new HashSet<>();
		List<Map<String, Object>> irComponents = new ArrayList<>();
		int row = 0;
		int cols = 1;
		for (Map.Entry<String, List<MdEntry>> layer : byLayer.entrySet()) {
			int col = 0;
			List<MdEntry> members = layer.getValue();
			if (members.size() > MAX_COLS) {
				// a wide layer wraps into several rows, callers above callees, so edges do not cross unrelated boxes
				members = members.stream()
					.sorted(Comparator.comparingInt((MdEntry c) -> depth.getOrDefault(c.id(), 0)).thenComparing(MdEntry::id))
					.toList();
			}
			for (MdEntry c : members) {
				if (col == MAX_COLS) {
					col = 0;
					row++;
				}
				ids.add(c.id());
				Map<String, Object> comp = new LinkedHashMap<>();
				comp.put("id", c.id());
				comp.put("type", layer.getKey());
				comp.put("label", fit(c.text()));
				// the technology (framework, product) is what the reader wants under the name; the id is the fallback
				String technology = c.attrs().get("technology");
				comp.put("sublabel", technology == null || technology.isBlank() ? c.id() : fit(technology.strip()));
				String language = c.attrs().get("language");
				if (language != null && !language.isBlank()) {
					comp.put("tag", fit(language.strip()));
				}
				comp.put("row", row);
				comp.put("col", col);
				comp.put("size", List.of(BOX_WIDTH, 60));
				irComponents.add(comp);
				col++;
				cols = Math.max(cols, col);
			}
			cols = Math.max(cols, col);
			row++;
		}

		Map<String, Object> ir = new LinkedHashMap<>();
		ir.put("schema_version", 1);
		ir.put("diagram_type", "architecture");
		ir.put("meta", Map.of("title", title, "output", "architecture.html"));
		ir.put("layout", Map.of("mode", "grid", "cols", cols, "cellW", BOX_WIDTH));
		ir.put("components", irComponents);
		ir.put("connections", connections(components, ids));
		return ir;
	}

	/** How far each component sits below the callers that link to it (longest path; bounded, so cycles are safe). */
	private static Map<String, Integer> depths(List<MdEntry> components) {
		Map<String, Integer> depth = new HashMap<>();
		components.forEach(c -> depth.put(c.id(), 0));
		for (int pass = 0; pass < components.size(); pass++) {
			boolean changed = false;
			for (MdEntry c : components) {
				for (String target : c.list("links")) {
					if (depth.containsKey(target) && depth.get(target) < depth.get(c.id()) + 1
							&& depth.get(c.id()) < components.size()) {
						depth.put(target, depth.get(c.id()) + 1);
						changed = true;
					}
				}
			}
			if (!changed) {
				break;
			}
		}
		return depth;
	}

	/** Archify rejects labels wider than their box; the full name stays in the md file. */
	private static String fit(String label) {
		return label.length() <= MAX_LABEL_CHARS ? label : label.substring(0, MAX_LABEL_CHARS - 1).stripTrailing() + "…";
	}

	private List<Map<String, Object>> connections(List<MdEntry> components, Set<String> ids) {
		Set<String> seen = new LinkedHashSet<>();
		List<Map<String, Object>> out = new ArrayList<>();
		for (MdEntry c : components) {
			for (String target : c.list("links")) {
				if (!ids.contains(target) || target.equals(c.id()) || !seen.add(c.id() + ">" + target)) {
					continue;
				}
				Map<String, Object> conn = new LinkedHashMap<>();
				conn.put("id", c.id() + "-to-" + target);
				conn.put("from", c.id());
				conn.put("to", target);
				out.add(conn);
			}
		}
		return out;
	}

}

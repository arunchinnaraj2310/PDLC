package com.pdlc.assistant.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.Yaml;

/**
 * A project md file kept as plain lines. Entries are located by ID and patched in place,
 * so everything else in the file (prose, headings, comments) is never rewritten.
 */
public class MdDocument {

	private static final Pattern ENTRY = Pattern.compile("^- ([A-Z]{2,5}-\\d{3,}):[ ]?(.*)$");

	private static final Pattern ATTR = Pattern.compile("^\\s{2,}- ([A-Za-z_]+):[ ]?(.*)$");

	private static final Pattern VERSION = Pattern.compile("^version:\\s*(\\d+)\\s*$");

	private record Block(int start, int end, MdEntry entry) {
	}

	private final List<String> lines;

	private MdDocument(List<String> lines) {
		this.lines = lines;
	}

	public static MdDocument parse(String text) {
		return new MdDocument(new ArrayList<>(Arrays.asList(text.split("\\R", -1))));
	}

	@Override
	public String toString() {
		return String.join("\n", lines);
	}

	public Map<String, Object> frontMatter() {
		int body = bodyStart();
		if (body == 0) {
			return Map.of();
		}
		Object parsed = new Yaml().load(String.join("\n", lines.subList(1, body - 1)));
		if (parsed instanceof Map<?, ?> map) {
			Map<String, Object> out = new LinkedHashMap<>();
			map.forEach((k, v) -> out.put(String.valueOf(k), v));
			return out;
		}
		return Map.of();
	}

	public List<MdEntry> entries() {
		return blocks().stream().map(Block::entry).toList();
	}

	/** Next free ID for a prefix, e.g. {@code nextId("REQ")} gives REQ-004 after REQ-003. */
	public String nextId(String prefix) {
		int max = 0;
		for (MdEntry e : entries()) {
			if (e.id().startsWith(prefix + "-")) {
				max = Math.max(max, Integer.parseInt(e.id().substring(prefix.length() + 1)));
			}
		}
		return "%s-%03d".formatted(prefix, max + 1);
	}

	public void apply(PatchOp op) {
		List<Block> blocks = blocks();
		Block existing = blocks.stream().filter(b -> b.entry().id().equals(op.id())).findFirst().orElse(null);
		switch (op.op()) {
			case "add" -> {
				if (existing != null) {
					throw new IllegalArgumentException("Cannot add " + op.id() + ": already exists");
				}
				if (op.text() == null || op.text().isBlank()) {
					throw new IllegalArgumentException("Cannot add " + op.id() + ": text is required");
				}
				insert(blocks, clean(new MdEntry(op.id(), op.text().trim(), new LinkedHashMap<>(op.attrs()))));
			}
			case "update" -> {
				requireExisting(existing, op);
				MdEntry old = existing.entry();
				Map<String, String> attrs = new LinkedHashMap<>(old.attrs());
				op.attrs().forEach((k, v) -> attrs.put(k, v));
				MdEntry updated = clean(new MdEntry(old.id(), op.text() == null ? old.text() : op.text().trim(), attrs));
				lines.subList(existing.start(), existing.end()).clear();
				lines.addAll(existing.start(), updated.render());
			}
			case "remove" -> {
				requireExisting(existing, op);
				lines.subList(existing.start(), existing.end()).clear();
			}
			default -> throw new IllegalArgumentException("Unknown op '" + op.op() + "' (use add, update or remove)");
		}
	}

	/** Increments {@code version:} in the front matter, if there is one. */
	public void bumpVersion() {
		for (int i = 1; i < bodyStart() - 1; i++) {
			Matcher m = VERSION.matcher(lines.get(i));
			if (m.matches()) {
				lines.set(i, "version: " + (Integer.parseInt(m.group(1)) + 1));
				return;
			}
		}
	}

	private void insert(List<Block> blocks, MdEntry entry) {
		if (!blocks.isEmpty()) {
			lines.addAll(blocks.get(blocks.size() - 1).end(), entry.render());
			return;
		}
		int at = lines.size();
		while (at > 0 && lines.get(at - 1).isBlank()) {
			at--;
		}
		List<String> rendered = new ArrayList<>();
		rendered.add("");
		rendered.addAll(entry.render());
		lines.addAll(at, rendered);
		if (!lines.get(lines.size() - 1).isBlank()) {
			lines.add("");
		}
	}

	/** Drops attrs with blank values and flattens newlines so an entry always stays line-shaped. */
	private static MdEntry clean(MdEntry entry) {
		Map<String, String> attrs = new LinkedHashMap<>();
		entry.attrs().forEach((k, v) -> {
			if (v != null && !v.isBlank()) {
				attrs.put(k, oneLine(v));
			}
		});
		return new MdEntry(entry.id(), oneLine(entry.text()), attrs);
	}

	private static String oneLine(String s) {
		return s.replaceAll("\\s*\\R\\s*", " ").trim();
	}

	private static void requireExisting(Block existing, PatchOp op) {
		if (existing == null) {
			throw new IllegalArgumentException("Cannot " + op.op() + " " + op.id() + ": not found");
		}
	}

	private int bodyStart() {
		if (!lines.isEmpty() && lines.get(0).trim().equals("---")) {
			for (int i = 1; i < lines.size(); i++) {
				if (lines.get(i).trim().equals("---")) {
					return i + 1;
				}
			}
		}
		return 0;
	}

	private List<Block> blocks() {
		List<Block> out = new ArrayList<>();
		int i = bodyStart();
		while (i < lines.size()) {
			Matcher m = ENTRY.matcher(lines.get(i));
			if (!m.matches()) {
				i++;
				continue;
			}
			Map<String, String> attrs = new LinkedHashMap<>();
			int j = i + 1;
			while (j < lines.size()) {
				Matcher a = ATTR.matcher(lines.get(j));
				if (!a.matches()) {
					break;
				}
				attrs.put(a.group(1), a.group(2).trim());
				j++;
			}
			out.add(new Block(i, j, new MdEntry(m.group(1), m.group(2).trim(), attrs)));
			i = j;
		}
		return out;
	}

}

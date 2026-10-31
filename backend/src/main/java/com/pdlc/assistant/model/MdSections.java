package com.pdlc.assistant.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A narrative document as an ordered list of '## ' sections under its '# Title'. Edits are one bullet or one
 * paragraph at a time and touch only the named section; everything else stays exactly as it was.
 */
public final class MdSections {

	/** Lower-cased heading to [original heading, body]; insertion ordered. */
	private final Map<String, String[]> sections = new LinkedHashMap<>();

	private MdSections() {
	}

	/** Parses a document body (no front matter). The '# Title' line and any text before the first '## ' are dropped. */
	public static MdSections parse(String body) {
		MdSections doc = new MdSections();
		String heading = null;
		StringBuilder text = new StringBuilder();
		boolean inFence = false;
		for (String line : body.split("\\R", -1)) {
			if (line.stripLeading().startsWith("```")) {
				inFence = !inFence;
			}
			if (!inFence && line.startsWith("## ")) {
				doc.flush(heading, text);
				heading = line.substring(3).strip();
				text = new StringBuilder();
			}
			else if (heading != null) {
				text.append(line).append('\n');
			}
		}
		doc.flush(heading, text);
		return doc;
	}

	private void flush(String heading, StringBuilder text) {
		if (heading != null && !heading.isEmpty()) {
			sections.put(key(heading), new String[] { heading, text.toString().strip() });
		}
	}

	/** Replaces a section's body, creating the section if needed. */
	public void set(String heading, String text, List<String> order) {
		if (text == null || text.isBlank()) {
			return;
		}
		ensure(heading, order)[1] = text.strip();
	}

	/** Appends a bullet unless an equal one is already there. Returns false for a blank or duplicate bullet. */
	public boolean add(String heading, String bullet, List<String> order) {
		String line = bulletLine(bullet);
		if (line.isEmpty()) {
			return false;
		}
		String[] section = ensure(heading, order);
		for (String existing : section[1].split("\\R")) {
			if (normalize(existing).equals(normalize(line))) {
				return false;
			}
		}
		section[1] = section[1].isBlank() ? line : section[1] + "\n" + line;
		return true;
	}

	/**
	 * Removes the first top-level bullet that matches {@code text}, with the indented lines below it. A bullet
	 * matches when it equals the text or one starts with the other (so "US-002" or "Role" are enough). Returns
	 * whether anything was removed.
	 */
	public boolean removeBullet(String heading, String text) {
		String[] section = sections.get(key(cleanHeading(heading)));
		String wanted = normalize(text);
		if (section == null || wanted.length() < 2) {
			return false;
		}
		List<String> kept = new ArrayList<>();
		boolean removed = false;
		boolean skipping = false;
		for (String line : section[1].split("\\R")) {
			boolean topLevel = line.startsWith("- ");
			if (topLevel) {
				skipping = false;
			}
			if (skipping && (line.startsWith(" ") || line.isBlank())) {
				continue;
			}
			if (!removed && topLevel) {
				String have = normalize(line);
				if (have.equals(wanted) || have.startsWith(wanted) || (have.length() >= 12 && wanted.startsWith(have))) {
					removed = true;
					skipping = true;
					continue;
				}
			}
			kept.add(line);
		}
		if (removed) {
			section[1] = String.join("\n", kept).strip();
		}
		return removed;
	}

	public boolean removeSection(String heading) {
		return sections.remove(key(cleanHeading(heading))) != null;
	}

	/** True when the section exists and has some text under it. */
	public boolean hasBody(String heading) {
		String[] section = sections.get(key(cleanHeading(heading)));
		return section != null && !section[1].isBlank();
	}

	public boolean isEmpty() {
		return sections.isEmpty();
	}

	/** The whole document as markdown under its title, ready to be written with front matter. */
	public String render(String title) {
		List<String> parts = new ArrayList<>();
		parts.add("# " + title);
		sections.values().forEach(s -> parts.add("## " + s[0] + (s[1].isBlank() ? "" : "\n\n" + s[1])));
		return String.join("\n\n", parts);
	}

	/** Finds or creates a section; a new one goes before the first later section in {@code order}, else last. */
	private String[] ensure(String heading, List<String> order) {
		String clean = cleanHeading(heading);
		String[] found = sections.get(key(clean));
		if (found != null) {
			return found;
		}
		String[] created = new String[] { clean, "" };
		int rank = rank(order, clean);
		Map<String, String[]> reordered = new LinkedHashMap<>();
		boolean placed = false;
		for (Map.Entry<String, String[]> e : sections.entrySet()) {
			if (!placed && rank >= 0 && rank(order, e.getValue()[0]) > rank) {
				reordered.put(key(clean), created);
				placed = true;
			}
			reordered.put(e.getKey(), e.getValue());
		}
		if (!placed) {
			reordered.put(key(clean), created);
		}
		sections.clear();
		sections.putAll(reordered);
		return created;
	}

	private static String bulletLine(String text) {
		String clean = text == null ? "" : text.strip().replaceAll("\\s*\\R\\s*", " ").replaceFirst("^[-*]\\s+", "");
		return clean.isEmpty() ? "" : "- " + clean;
	}

	private static String normalize(String text) {
		return text.strip().replaceFirst("^[-*]\\s+", "").replaceAll("[\\s.]+$", "").toLowerCase(Locale.ROOT);
	}

	private static int rank(List<String> order, String heading) {
		for (int i = 0; i < order.size(); i++) {
			if (order.get(i).equalsIgnoreCase(heading)) {
				return i;
			}
		}
		return -1;
	}

	private static String cleanHeading(String heading) {
		return heading == null ? "" : heading.replaceFirst("^#+\\s*", "").strip();
	}

	private static String key(String heading) {
		return heading.toLowerCase(Locale.ROOT);
	}

}

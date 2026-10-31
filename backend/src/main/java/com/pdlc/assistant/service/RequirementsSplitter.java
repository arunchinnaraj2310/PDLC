package com.pdlc.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Local, no-LLM preparation of a requirements.md for the model: drops front matter, comments, images and
 * empty headings, then re-emits the remaining sections compactly so only meaningful text costs tokens.
 */
@Component
public class RequirementsSplitter {

	private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+\\S.*$");

	private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

	private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");

	private record Section(String heading, List<String> body) {
	}

	/** Returns the compact text to send to the model; throws if nothing usable is left. */
	public String compact(String markdown) {
		if (markdown == null || markdown.isBlank()) {
			throw new IllegalArgumentException("The requirements document is empty");
		}
		String text = COMMENT.matcher(stripFrontMatter(markdown)).replaceAll("");
		text = IMAGE.matcher(text).replaceAll("");

		List<Section> sections = new ArrayList<>();
		Section current = new Section(null, new ArrayList<>());
		boolean inFence = false;
		for (String raw : text.split("\\R")) {
			String line = raw.stripTrailing();
			if (line.stripLeading().startsWith("```")) {
				inFence = !inFence;
			}
			if (!inFence && HEADING.matcher(line).matches()) {
				sections.add(current);
				current = new Section(line, new ArrayList<>());
			}
			else if (!line.isBlank()) {
				current.body().add(line);
			}
		}
		sections.add(current);

		StringBuilder out = new StringBuilder();
		for (Section s : sections) {
			if (s.body().isEmpty()) {
				continue;
			}
			if (s.heading() != null) {
				out.append(s.heading()).append('\n');
			}
			s.body().forEach(l -> out.append(l).append('\n'));
			out.append('\n');
		}
		if (out.isEmpty()) {
			throw new IllegalArgumentException("The requirements document has no text under its headings");
		}
		return out.toString().strip();
	}

	private static String stripFrontMatter(String md) {
		String[] lines = md.split("\\R", -1);
		if (lines.length > 0 && lines[0].trim().equals("---")) {
			for (int i = 1; i < lines.length; i++) {
				if (lines[i].trim().equals("---")) {
					return String.join("\n", List.of(lines).subList(i + 1, lines.length));
				}
			}
		}
		return md;
	}

}

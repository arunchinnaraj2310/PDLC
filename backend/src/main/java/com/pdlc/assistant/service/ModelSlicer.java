package com.pdlc.assistant.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.pdlc.assistant.model.MdEntry;

/**
 * Builds the slice of the project model sent with a change request: a one-line outline of every entry (so the
 * model can reference anything by id) plus full detail only for entries the request mentions and their
 * one-hop neighbours. Deterministic keyword matching, no LLM. This keeps the prompt small as projects grow.
 */
@Component
public class ModelSlicer {

	private static final int OUTLINE_REQ_CHARS = 90;

	private static final int STEM_CHARS = 4;

	private static final Set<String> STOP_WORDS = Set.of("add", "please", "should", "would", "could", "that", "this",
			"with", "from", "have", "need", "want", "make", "also", "into", "using", "like", "just", "only", "them",
			"they", "there", "their", "what", "which", "when", "where", "will", "about", "more", "some", "each",
			"every", "other", "system", "application", "service", "services", "component", "components",
			"requirement", "requirements", "design", "architecture", "feature", "support", "allow", "user", "users");

	public String slice(List<MdEntry> reqs, List<MdEntry> cmps, String message) {
		Set<String> stems = stems(message);
		Set<String> reqs1 = new LinkedHashSet<>();
		Set<String> core = new LinkedHashSet<>();
		reqs.stream().filter(r -> matches(r, stems)).forEach(r -> reqs1.add(r.id()));
		cmps.stream().filter(c -> matches(c, stems)).forEach(c -> core.add(c.id()));

		// one hop: what the matched components link to or satisfy, who links to them, who satisfies matched requirements
		Set<String> selectedCmps = new LinkedHashSet<>(core);
		Set<String> selectedReqs = new LinkedHashSet<>(reqs1);
		for (MdEntry c : cmps) {
			if (core.contains(c.id())) {
				selectedCmps.addAll(c.list("links"));
				selectedReqs.addAll(c.list("satisfies"));
			}
			else if (c.list("links").stream().anyMatch(core::contains)
					|| c.list("satisfies").stream().anyMatch(reqs1::contains)) {
				selectedCmps.add(c.id());
			}
		}

		StringBuilder out = new StringBuilder("Requirements (outline):\n");
		reqs.forEach(r -> out.append("- ").append(r.id()).append(": ").append(shorten(r.text())).append('\n'));
		if (reqs.isEmpty()) {
			out.append("(none)\n");
		}
		out.append("\nComponents (outline):\n");
		cmps.forEach(c -> out.append("- ").append(c.id()).append(": ").append(c.text()).append(" [")
			.append(c.attrs().getOrDefault("layer", "?")).append("]\n"));
		if (cmps.isEmpty()) {
			out.append("(none)\n");
		}

		out.append("\nDetail of the entries related to the request:\n");
		boolean any = false;
		for (MdEntry r : reqs) {
			if (selectedReqs.contains(r.id())) {
				out.append("- ").append(r.id()).append(": ").append(r.text()).append('\n');
				any = true;
			}
		}
		for (MdEntry c : cmps) {
			if (selectedCmps.contains(c.id())) {
				out.append("- ").append(c.id()).append(": ").append(c.text());
				c.attrs().forEach((k, v) -> out.append(" | ").append(k).append(": ").append(v));
				out.append('\n');
				any = true;
			}
		}
		if (!any) {
			out.append("(no existing entry matches the wording of the request)\n");
		}
		return out.toString().stripTrailing();
	}

	private static String shorten(String text) {
		return text.length() <= OUTLINE_REQ_CHARS ? text : text.substring(0, OUTLINE_REQ_CHARS) + "…";
	}

	/** Lower-cased words of the message cut to a short stem, so "caching" also finds "cache". */
	private static Set<String> stems(String message) {
		return Arrays.stream(message.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
			.filter(w -> w.length() >= STEM_CHARS && !STOP_WORDS.contains(w))
			.map(w -> w.length() > STEM_CHARS + 1 ? w.substring(0, STEM_CHARS) : w)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static boolean matches(MdEntry entry, Set<String> stems) {
		String haystack = (entry.id() + " " + entry.text() + " " + String.join(" ", entry.attrs().values()))
			.toLowerCase(Locale.ROOT);
		return stems.stream().anyMatch(haystack::contains);
	}

}

package com.pdlc.assistant.spike;

import java.util.List;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.pdlc.assistant.dto.LlmRequest;
import com.pdlc.assistant.service.LlmGateway;

/**
 * Manual smoke check for the LLM gateway (no Spring needed). Needs OPENAI_API_KEY in the environment
 * for checks 1 and 3; check 2 (oversize rejection) works without it. Throwaway, like the rest of spike/.
 */
public class GatewaySpike {

	public static class Req {
		public String id;
		public String text;
	}

	public static class Component {
		public String name;
		public Layer layer;
		public String responsibility;
		public List<String> satisfies;
	}

	/** An enum in the schema forces the model to pick a valid Archify type (a plain String let "cache" through). */
	public enum Layer {
		frontend, backend, database, cloud, security, messagebus, external
	}

	public static class DesignDraft {
		public List<Req> reqs;
		public List<Component> components;
	}

	private static final String SYSTEM = "Extract requirements (ids REQ-001.., one per capability) and architecture "
			+ "components from the text.";

	private static final String TEXT = "Todo app: users sign in with OAuth2, create and complete todos in a web UI, "
			+ "data is stored in PostgreSQL and hot reads are cached in Redis.";

	public static void main(String[] args) {
		LlmGateway gateway = new LlmGateway(OpenAIOkHttpClient.builder().fromEnv().build(),
				System.getenv().getOrDefault("PDLC_MODEL", "gpt-4o-mini"), 1000, 6000);

		System.out.println("== 1. normal call");
		DesignDraft draft = gateway.complete(LlmRequest.of("spike-extract", SYSTEM, TEXT), DesignDraft.class);
		draft.reqs.forEach(r -> System.out.println(r.id + ": " + r.text));
		draft.components.forEach(c -> System.out.println(c.layer + " | " + c.name + " | satisfies " + c.satisfies));

		System.out.println("== 2. oversize input must be rejected before any API call");
		try {
			gateway.complete(LlmRequest.of("spike-oversize", SYSTEM, "x".repeat(40_000)), DesignDraft.class);
			System.out.println("FAIL: oversize input was accepted");
		}
		catch (IllegalArgumentException e) {
			System.out.println("OK: " + e.getMessage());
		}

		System.out.println("== 3. truncated output must be retried exactly once, then fail");
		try {
			gateway.complete(new LlmRequest("spike-truncate", SYSTEM, TEXT, null, 30), DesignDraft.class);
			System.out.println("FAIL: truncated output was accepted");
		}
		catch (RuntimeException e) {
			System.out.println("OK: " + e.getMessage() + " (log above should show attempt=1 and attempt=2 only)");
		}
	}

}

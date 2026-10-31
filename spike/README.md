# Phase 0 spike findings

## Archify: GO (v3.2.0, vendored at `tools/archify/`, Node 20 works; needs >=18)
Run from the repo root, never from inside `tools/archify`:
```
node tools/archify/archify/bin/archify.mjs validate architecture spike/todo-nolabel.architecture.json --json
node tools/archify/archify/bin/archify.mjs deliver  architecture spike/todo-nolabel.architecture.json spike/out/todo.html --json
```
- Output is one self-contained HTML file (~830 KB, no external fetches), so it can be served into an `<iframe>`.
- Component `type` is a fixed enum: `frontend|backend|database|cloud|security|messagebus|external`. The IrMapper maps CMP `layer` onto it.
- **Layout is not automatic.** Without positions the renderer crashes (`validate` still passes the schema). Fix: set top-level `"layout": {"mode":"grid","cols":N}` and give each component `row`/`col`. The IrMapper must derive row from layer and col from order within the layer.
- **`validate` is strict about edge labels.** Labels on vertical edges overlap the component boxes and fail with `layout/constraint`. Spike answer: omit connection labels in the first cut. Retry later with `labelDy` or `labelAt` if needed (the error text suggests values).
- `deliver` runs an update check (writes `update.checkedAt`), which may make a network call. Check this before any offline or locked-down deploy.
- `deliver` also writes `<name>.delivery.json` next to the HTML. Run it in a temp dir and copy only the HTML.

## OpenAI structured output: GO (run on gpt-4o-mini, 189 prompt + 177 completion tokens)
Result parsed straight into `DesignDraft`. Caveat: `layer` was declared as a plain `String`, so the model returned `cache`, which is not an Archify type. In the Gateway, declare layer as a Java `enum` so the schema enforces the allowed values. It also folded everything into one REQ, so the prompt needs to ask for one REQ per capability.

How to rerun:
The original spike class was replaced in step 2 by `spike/GatewaySpike.java`, which does the same call through `LlmGateway` (`openai-java` 4.94.0) and adds the oversize and retry checks.
```
export OPENAI_API_KEY=sk-...            # optional: PDLC_MODEL=gpt-4o-mini
cd backend && ./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" com.pdlc.assistant.spike.GatewaySpike   # use ';' instead of ':' on Windows cmd/PowerShell
```
Expected: 2-4 REQ lines, 4-5 component lines, and a token line. Exit criterion is that the call returns a parsed `DesignDraft`.

## Step 4 observations (requirements.md -> design, sample: samples/todo-requirements.md)
- Pipeline works end to end in about 7 s and about 600 prompt + 350 completion tokens on gpt-4o-mini.
- Extraction is non-deterministic. Over 6 runs on the same input (3 each), only 2 gave full requirement coverage with no warnings. Typical misses: no cache component for a "cached" requirement, a component with no links, a requirement mapped to the wrong component. The deterministic warnings (unsatisfied REQ, isolated component) catch these, and nothing is written until Apply.
- gpt-4.1 is more thorough (adds an OAuth component and a cache, more components) at about 35% more completion tokens, but is also not stable. gpt-4o-mini stays the default; switch with PDLC_MODEL.
- Prompt changes that helped: a connectivity rule (UI -> API -> data), "add a component if a requirement needs a capability nothing provides", and a note that messagebus is only for queues/brokers.
- Not tried: temperature 0 (reasoning models reject it, so it needs a per-model switch in the gateway).

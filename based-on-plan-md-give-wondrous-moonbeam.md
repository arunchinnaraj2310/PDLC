# PDLC POC: simplified step-by-step build plan

## Context
PLAN.md and tech-stack.md describe the full POC. The goal is to find out how well we can **reverse engineer a design (components + diagram) from three inputs**:
1. existing **code**,
2. **chat** (conversation about intent or changes),
3. a **requirements.md** file.

So build the thinnest vertical slice that proves this, and defer everything else. Where the full docs conflict with "keep it simple", this plan wins for the first cut. The deferred items are added back once the core works.

## What to defer (add back only after the core works)
| Full plan | First cut |
|---|---|
| S3 + versioning | Local folder (`storage.mode=local`, already in tech-stack.md). Undo = timestamped copy of the md before apply. |
| Login, Docker, Caddy, Lightsail | None. `mvn spring-boot:run` + `npm run dev`. |
| SQLite ledger, budgets, Caffeine cache | Log token counts to the console. One hard `max_tokens` and an input size cap. |
| Cheap-model router | One model, configurable. |
| Code generation (phase 6) | Skipped. It is not needed to test reverse engineering. |
| Cross-project FE↔BE linking, foundation band, drift report | Phase C extras, only if time allows. |
| Tests / observability | Out of scope per CLAUDE.md. Manual smoke checks only. |

Keep: the **LLM Gateway** as one small class (the rule is never to bypass it), **patch-not-rewrite**, **diagrams derived from md**, and **deterministic-first** extraction.

## Core idea: one pipeline, three entry points
All three inputs end at the same place: **`docs/11-building-blocks.md` (CMP list with `satisfies: REQ-…`) → diagram**.

```
code ───────► facts.json ► clusters ► LLM labels ┐
requirements.md ► LLM extracts REQ + CMP ────────┼► building-blocks patch ► Apply/Reject ► IR ► Archify HTML
chat ────────► LLM tool call (propose_doc_patch) ┘
```

## Steps

### Step 0: Spike (about half a day, go/no-go)
- Install Node 22. Vendor Archify into `tools/archify/`. Hand-write an Architecture IR, then run `validate` and `deliver`.
- Confirm the IR schema and CLI contract. Check whether the HTML works in an `<iframe>`.
- One OpenAI structured-output call from Java (JSON schema → parsed POJO).
- **Exit:** Archify works, or switch to Mermaid and update both docs.

### Step 1: Backend skeleton and md model (minimal)
- Spring Boot 4 / Java 17 / Maven project. Local-folder storage with a `ProjectStore` interface so S3 can be added later.
- Project folder layout as in PLAN.md, but only create `docs/04-technical-requirements.md`, `docs/11-building-blocks.md`, `diagrams/`, `.pdlc/facts.json`, `changelog.md`.
- Parser for md with front matter and stable IDs (`REQ-###`, `CMP-###`) using snakeyaml and plain regex on `- REQ-001: …` lines. Skip commonmark unless it's needed.
- `applyPatch(file, op)`: add, update or remove an entry by ID. It writes a backup copy and a changelog line first.

### Step 2: LLM Gateway (about 1 class)
- Wraps `openai-java`. Takes a prompt, a JSON schema and a model name, and returns a typed object.
- Enforces `max_tokens`, an input-size cap, one retry on schema failure, and a console token log.
- Every later step calls only this.

### Step 3: Model → diagram (deterministic)
- `IrMapper`: parse `11-building-blocks.md` into Archify Architecture IR (layers client/API/services/data/external from a `layer:` field on each CMP).
- `ArchifyRunner`: `ProcessBuilder` runs `validate` and then `deliver` in a temp dir with a timeout. It writes `diagrams/architecture.html`.
- Endpoint `GET /projects/{id}/diagram`.
- Smoke: hand-edit a CMP in md and the diagram changes.

### Step 4: Entry point 1: requirements.md → design (cheapest to prove first)
- Endpoint: upload or point to `requirements.md`.
- Split it into sections locally (no LLM). Send compact sections to the LLM with a schema `{reqs:[…], components:[{name, layer, responsibility, satisfies:[REQ]}], links:[…]}`.
- Backend turns the result into a patch for `04-technical-requirements.md` and `11-building-blocks.md`, validates it, and shows an Apply/Reject diff. On apply it re-renders the diagram.
- Smoke: the sample todo-app requirements give a sensible diagram.

### Step 5: Minimal UI
- React + Vite + Tailwind in the repo. Left side is a plain chat. Right side is the diagram `<iframe>`. Diff card with Apply/Reject via the `diff` package.
- Start with **non-streaming** chat. Add SSE streaming later (it is a small change).
- Build into `static/` only when you want a single jar.

### Step 6: Entry point 2: chat → design
- Chat endpoint: last K turns plus a **sliced** model (affected IDs + 1-hop neighbours, not whole docs).
- A single tool, `propose_doc_patch`, returns REQ/CMP patch ops. It reuses the Step 4 patch → Apply/Reject → re-render path.
- Smoke: "add OAuth2 login" adds an Auth component and a link. "Add Redis caching" adds a cache node. Undo restores the previous diagram.
- Check that token use stays below the PLAN.md targets (<2k prompt, <500 completion).

### Step 7: Entry point 3: code → design (the main experiment)
Build in this order, so each stage can be inspected on its own:
1. **Inventory** (Java): file tree, SHA-256 hashes, parse `pom.xml` / `package.json` / `application.yml` / `docker-compose.yml`. Apply a `rules.yml` that maps dependency to component type (redis → cache, postgres → DB, spring-security → auth). *This alone gives the external-systems part of the diagram with no LLM.*
2. **Extractor** `tools/extract/` (Node + tree-sitter, Java and TS/JS only): emit `facts.json` with nodes and edges, each with `file:line` evidence. It extracts imports, routes and entities.
3. **Clustering** (plain Java): group by package/folder and layer heuristic (controller/service/repository/ui) into about 8–15 components. Node IDs come from path keys.
4. **LLM labelling:** send only compact facts per cluster (a few hundred tokens). It returns a display name, a one-line responsibility and a layer. Cache by facts hash, in a Java `Map` or a file, not Caffeine.
5. **Reconcile:** diff against `11-building-blocks.md` and emit an Apply/Reject patch. Components in code but not in the requirements are shown as drift. REQs with no component are shown as unimplemented.
6. **Manual "Sync" button** (no watcher). Re-extract only files whose hash changed.
- Smoke: run on one small Spring repo and one small TS repo.
- **Deferred inside step 7 (Phase C):** DI/interface resolution, the foundation band for utils, and cross-project URL matching. Add them one at a time once the basic loop works.

### Step 8: Evaluate (the actual goal)
Run the same sample project through all three entry points and compare the resulting diagrams and md:
- **Fidelity:** are components and edges correct? Does `file:line` evidence back them?
- **Cost:** tokens per entry point.
- **Gaps:** what the code path misses (reflection, dynamic URLs) and what the requirements path invents.
- Write the findings to a short `FINDINGS.md`. Update the "Open questions" in PLAN.md and tech-stack.md.

## Suggested order and checkpoints
`0 → 1 → 2 → 3` gives a working md → diagram engine. `4` is the first end-to-end demo. `5 → 6` completes the chat. `7` is the heavy part. `8` is the conclusion.
Reverse engineering can be demoed earlier: do 0–3, then 7.1–7.3, with CLI-only output, before building the UI.

## Verification
Manual only, per CLAUDE.md:
- Step 0: `archify validate` passes and the HTML opens.
- Step 3: editing an md CMP changes the rendered diagram.
- Step 4/6: scenarios (a)–(c) in PLAN.md "Verification", plus the oversize-message rejection and the single retry on a malformed response.
- Step 7: sample-repo checks from PLAN.md (Spring `IUserRepository` resolution, utils star topology) once Phase C items are added.

## Housekeeping
- Update CLAUDE.md with build/run commands after Step 1.
- Record in PLAN.md that the first cut uses local storage with no auth, and keep tech-stack.md consistent with it.

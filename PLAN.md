# PDLC Assistant — MVP/POC Plan

## Context
Build a reusable PDLC tool for any software project, operated by a **single admin user** (no multi-user/roles). A chatbot (OpenAI LLM) captures **product intent** and produces three artifacts: **Requirements**, **Design** (with diagrams), **Implementation** (frontend + backend code). The key differentiator is **living traceability**: a change in requirements (e.g. "add OAuth2", "add Redis caching") or in code automatically updates the design diagram. Token usage must be minimal, enforced by guardrails.
Tech stack is deferred; this plan is stack-agnostic with a recommended default at the end. Working dir `c:\Users\Zlendo\Downloads\Java\JavaProjects\claude\PDLC` is empty (greenfield).

## Key finding: Archify (github.com/tt-a1i/archify)
- Apache-2.0 agent skill + zero-dependency **Node CLI** (`bin/archify.mjs`: `validate`, `preview`, `deliver`, `compare`, ...).
- Input is a **typed JSON IR** per diagram mode (Architecture, Workflow, Sequence, Data Flow, ERD, Class, ...). Output is self-contained interactive HTML/SVG (+ PNG/PDF exports).
- Needs Node 18+/22+.
- **Implication:** the diagram is a *deterministic render of a JSON model*. The LLM never draws; it only emits small **patches to the IR**. This is both the token-saving strategy and the change-propagation mechanism. `validate` doubles as a guardrail (reject malformed LLM output), `compare` can show before/after diffs.
- Risk to verify first: exact IR schema per mode and CLI contract (spike in step 0).

## Core design: single source of truth = Project Model
Store per project a structured model (not prose), versioned:
1. `intent` — vision, goals, users, success metrics
2. `requirements` — overview, business problem, product description, technical requirements (functional / non-functional / constraints), each item with stable ID (`REQ-007`)
3. `design` — system design, building blocks (components), architecture decisions, each with ID (`CMP-003`) and `satisfies: [REQ-ids]`
4. `diagram IR` — Archify architecture JSON, derived from `design` components/links (not hand-edited)
5. `implementation` — generated files, mapped `implements: [CMP-ids]`
6. `changelog` — every change + version, for audit/undo

**All project artifacts are Markdown files** (the md files ARE the model — no hidden DB for project content). Each project is a folder:
```
<project>/
  docs/
    00-intent.md            vision, goals, users, success metrics
    01-overview.md
    02-business-problem.md
    03-product-description.md
    04-technical-requirements.md   items with IDs: REQ-001 ...
    10-system-design.md
    11-building-blocks.md          components CMP-001 ... (+ `satisfies: REQ-..`)
    12-architecture.md             decisions (ADR-style) + embedded diagram link
    13-design-diagrams.md          links to diagrams/*.html
    changelog.md                   every change, version, reason
  diagrams/architecture.ir.json    DERIVED from md (Archify IR)  + architecture.html (rendered)
  src/                             generated frontend + backend code
  .pdlc/ (chats/*.md, usage.md, facts.json cache, state)  — internal, not user docs
```
- Each md file: YAML front matter (`id`, `version`, `status`) + stable-ID bullet/table entries, so the machine parses structure from md and the LLM edits **by section/ID** (patch a heading or entry, never rewrite the file).
- Versioning/undo: `git init` in the project folder (auto-commit per accepted change) — md diffs are human-readable; undo = revert. (Falls back to `.pdlc/history` copies if git is absent.)
- Diagrams are **rendered views** of the md model; IR is regenerated, never hand-edited.

## Chatbot behaviour (must work like Claude chat)
- Free-form, multi-turn, **streaming** responses (SSE), markdown rendering with code blocks, Stop / Regenerate / Edit-last-message, multiple saved conversations per project (stored as md in `.pdlc/chats`).
- The LLM converses and asks clarifying questions (product-intent interview: problem → users → goals → constraints), not a rigid form wizard.
- Changes to project docs are made via **tool calls** (`propose_doc_patch`, `update_diagram`, `generate_code`); the UI shows a diff card "Apply / Reject" inline in the chat; applied changes write the md + re-render the diagram live beside the chat.
- Plain Q&A about the project answers from the md slice without proposing patches.
- Token guardrails still apply: history = last K turns + rolling summary; project context = sliced md sections, not whole docs.

## Code → design: recommended reverse-engineering approach
**Hybrid, deterministic-first ("facts, then names")** — LLM never reads whole source.
1. **Inventory (free):** file tree + content hashes; parse manifests/config (`pom.xml`, `build.gradle`, `package.json`, `requirements.txt`, `application.yml`, `.env.example`, `docker-compose.yml`, k8s/Dockerfile). Gives tech stack and **external systems** (redis→cache, postgresql→DB, kafka→queue, s3→storage, spring-security/passport/oauth→auth) via a small rules table (dependency → component type).
2. **Static extraction (free):** **tree-sitter** parsers (multi-language, no build needed) with per-language extractor plugins: imports/package dependency graph, entry points, HTTP routes (`@RestController/@GetMapping`, Express/FastAPI routers), entities/tables, outbound clients (`RestTemplate`, `fetch`, JDBC/Redis clients). Output `facts.json`: nodes + edges each with **evidence** (`file:line`). MVP languages: Java and TypeScript/JavaScript (add Python later).
   - **DI / interface resolution pass (free):** runs after raw edges are built, before clustering. Builds an interface→implementation map in `facts.json` (`implementations: {IUserRepository: [PostgresUserRepository]}`) so an import of `IUserRepository` is rewritten to the concrete data-layer class. Rules in priority order: (a) DI registration — Spring `@Component/@Service/@Repository/@Bean` (+ `@Primary`/`@Qualifier`), NestJS `@Module({providers:[{provide, useClass}]})`/`@Injectable`; (b) explicit `implements`/`extends` found by tree-sitter; (c) naming convention `IX`→`X`/`XImpl`/`DefaultX`/`XAdapter`. Each resolved edge records `resolvedVia: di|implements|naming` and `confidence` (di/implements = high; naming-only or multiple candidates = low, all candidates listed).
   - **Endpoint facts:** record raw URL strings for outbound clients (`fetch`, `axios`, `RestTemplate`, `WebClient`, `@FeignClient`) and inbound routes (`@GetMapping` etc., Express/Nest routes) with HTTP method, a normalized template (`/users/{id}` ≡ `/users/:id` ≡ `/users/${id}`) and `file:line` evidence. Used by step 8.
3. **Component clustering (free):** group files into building blocks by package/folder boundaries and layer heuristics (controller/service/repository/ui); collapse to ~8–15 nodes so the diagram stays readable; edges aggregated from import counts. Node IDs derived from stable path keys → no churn between runs.
   - **Shared/utils isolation:** clusters matching `utils|shared|common|lib|helpers` (configurable globs) **or** with fan-in above a threshold (e.g. imported by >50% of other clusters) are classified as a global **foundation layer**. They are excluded from macro-component edge aggregation (no per-component edges → no "star" topology) and rendered as a single "Shared / Foundation" band at the bottom of the diagram. Their detailed edges stay in `facts.json` for the drift report.
4. **LLM labelling (cheap):** send only compact facts per cluster (names, routes, dependencies — a few hundred tokens), ask for: display name, one-line responsibility, ambiguous edge types (sync/async), layer. Cached by facts hash; skipped if cluster unchanged. User-edited names/descriptions in md are **pinned** and never overwritten.
5. **Reconcile with md:** diff new facts vs `11-building-blocks.md`; emit a patch (add/remove/modify CMP + links) → shown as Apply/Reject → on apply regenerate `architecture.ir.json` → `archify validate` → render. Components found in code but absent in requirements = *drift warning*; REQs with no component = *unimplemented* report.
6. **Incremental:** on "Sync" (or on generated-code writes) use `git diff`/hash comparison, re-extract only changed files, re-run steps 3–5 for affected clusters only.
7. **Diagram mapping:** facts → Archify **Architecture** IR (layers: client / API / services / data / external). Same facts later feed Sequence (route→service→repo call chains), Data Flow and ERD (entities) modes.
8. **Cross-project linking (polyglot / multi-repo, free):** projects (e.g. TS frontend + Java backend) are registered as a workspace list in `.pdlc/`. A deterministic matching engine compares outbound client URLs with inbound route templates from step 2 (method + normalized template, path params as wildcards, configurable base-URL/`/api` prefix stripping). Matches become cross-project edges with `confidence`: exact template match = medium, wildcard/prefix-only = low; never "high" without user confirmation. Rendered **dashed** in the diagram; user confirms/rejects via chat (pinned in md like other user edits). Unmatched outbound URLs are listed in the drift report as "unresolved external calls".
- Why not alternatives: pure-LLM code reading = most tokens, non-deterministic, hallucinated edges; pure static = correct but unnamed/too granular; language-specific tools (jdeps, dependency-cruiser, madge) can be plugged in as extractors where available but give no unified output.
- Honest limits: DI wiring, shared-utils noise and cross-service calls are now partly mitigated by the DI resolution pass (step 2), foundation-layer isolation (step 3) and cross-project linking (step 8). Residual limits: reflection, dynamic dispatch, dynamically built URLs and config-driven service discovery — flagged as low-confidence edges (dashed in diagram) for user confirmation via chat.

## Change propagation flows
- **Requirement change (chat):** user message → intent classifier → LLM emits JSON Patch on `requirements` + proposed patch on `design` components (e.g. adds `CMP: Auth Service`, `CMP: Redis Cache`, links) → patch validated → user confirms → model updated → IR regenerated → Archify re-renders → diff highlighted.
- **Code change:** file-change input (upload/git diff/watch folder) → **deterministic static extraction first** (imports, dependencies in manifest, route/controller decls, config, e.g. detects `redis` dependency) → only ambiguous leftovers go to LLM as a compact diff summary → patch on `design` → re-render. Flags drift where code and requirements disagree. Interface→implementation resolution and cross-project edge matching are re-evaluated for affected clusters on each sync.
- **Design edit → requirement/code impact:** list impacted REQ/CMP IDs via traceability links (no LLM needed).

## Token-minimization guardrails (explicit)
1. **Patch, don't regenerate:** LLM returns JSON Patch ops only, never full documents/diagrams.
2. **Structured outputs** (JSON schema / function calling) + hard `max_tokens` per call type; reject/retry once on schema failure (retry budget 1).
3. **Context slicing:** send only the relevant model slice (affected IDs + 1-hop neighbours), not the whole project or chat history. Rolling conversation summary capped at N tokens.
4. **Deterministic-first:** regex/AST/manifest parsing, templates and Archify rendering handle everything that doesn't need judgment; LLM called only for intent understanding and content authoring.
5. **Intent router with cheap model** (e.g. small gpt-4o-mini class) for classification; stronger model only for code generation. Model per task configurable.
6. **Caching:** hash(prompt slice) → response cache; stable prefix first to benefit from OpenAI prompt caching.
7. **Budgets:** per-request, per-session, per-project and global (admin-wide, e.g. monthly) token caps; pre-flight token estimate (tiktoken-style) blocks oversize calls; user-visible usage meter; graceful "budget reached" response.
8. **Input limits:** max message length, max diff size (truncate to changed hunks), ignore lockfiles/generated/binary files.
9. **Code generation per component** (one file/module per call) with design slice only, to avoid huge contexts.
10. **Usage ledger:** log prompt/completion tokens per call → surfaced in the UI usage meter.

## MVP scope (in)
- Chat UI + OpenAI integration (server-side key, never in browser).
- Guided capture of: Overview, Business Problem, Product Description, Technical Requirements → saved as model + exportable Markdown.
- Design generation: system design summary, building blocks list, architecture decisions; **architecture diagram via Archify** (one diagram mode: Architecture; optionally Sequence if the spike is cheap).
- Chat-driven requirement change → design + diagram update with before/after diff and confirm step.
- Code generation: backend + frontend skeleton per component for a chosen language/framework, written to a project folder.
- Code-change → diagram update via folder scan/manual "sync" button (static extraction + LLM fallback).
- Traceability IDs (REQ ↔ CMP ↔ file) and changelog with undo to previous version.
- Token guardrails 1–10 above, with usage meter.
- Streaming, multi-turn Claude-style chat with tool-driven doc patches and inline Apply/Reject.
- All project content persisted as md files in a per-project S3 prefix (S3 object versioning + `changelog.md` for undo; see tech-stack.md).
- Code→design reverse engineering for Java + TS/JS using the hybrid approach above, including DI/interface resolution, shared/utils foundation-layer isolation and dashed low-confidence cross-project (frontend↔backend) edges.
- **Single-admin application:** exactly one admin user owns and operates everything (all projects, chats, budgets, settings, diagram confirmations). No user management, roles, teams or sharing. The admin must sign in (single pre-provisioned account, no self-registration) because the app is deployed on AWS; all API routes require the admin session. Projects are not scoped per user — `projectId` is the only partition key, and no `userId` column/prefix is modelled.

## Out of scope (MVP)
- Multi-user support, additional accounts, roles/permissions, teams, sharing, real-time collaboration, user-management UI (only the single admin login exists, with credentials from environment config; no self-registration, invitations or password-reset flows)
- SaaS productisation, multi-tenancy, billing (the app itself is deployed on AWS for the single admin — see tech-stack.md)
- Other LLM providers (Anthropic, Gemini, local models) — OpenAI only, behind a thin interface for later
- Diagram modes beyond Architecture (+ maybe Sequence): ERD, Class, Workflow, Timeline etc.
- Manual drag-and-drop diagram editing (diagram is read-only render; edits go through chat)
- Continuous file-watcher / git hook / CI integration (MVP uses manual sync)
- Full-fidelity reverse engineering of arbitrary codebases/languages (MVP: Java + TS/JS extractors only; others unsupported or LLM-summary best effort); runtime/trace-based analysis; precise call-graph through reflection/DI
- Chat features beyond core: file/image attachments, voice, web search, sharing/export of conversations, artifacts/canvas editing
- Executing, building, testing or deploying generated code; CI/CD, IaC, Docker generation
- Generating production-grade/complete apps (output is a skeleton/POC-quality)
- Later PDLC phases: test plans, QA, release, monitoring, maintenance, project management (tasks, sprints, estimates, Jira/GitHub sync)
- Import of existing PRDs/docs (PDF/Word/Confluence), export to Word/PDF/Confluence
- Security hardening beyond basics (SSO, audit compliance, encryption at rest, PII redaction), i18n, accessibility audit, mobile UI
- Fine-tuning, RAG/vector DB, agents with autonomous multi-step tool loops
- Performance/scale targets
- Automated testing (unit, integration, E2E, snapshot) and test tooling — manual smoke checks only
- Observability (metrics, tracing, alarms, dashboards) — default console logs only

## Implementation phases
0. **Spike (½ day):** install Archify, hand-write an Architecture IR, run `validate` + `deliver`; confirm schema, output embeddability (iframe vs inline HTML), runtime needs. Confirm OpenAI structured-output call works. Decide go/no-go on Archify integration.
1. **Project md model:** md templates + front-matter schema, ID scheme, section/ID-level patch apply + validate, S3-versioning-based changelog/undo.
2. **LLM gateway:** single module wrapping OpenAI — model routing, schema-enforced output, token estimate, budgets, cache, usage ledger. All LLM calls go through it (enforces guardrails in one place).
3. **Requirements capture flow:** conversational prompts → model + Markdown render.
4. **Design + diagram:** component derivation, model→Archify IR mapper (deterministic), render via Archify CLI, display in UI, diff highlight.
5. **Change propagation (requirements):** intent router, patch proposals, confirm/undo.
6. **Code generation:** per-component generation to output folder with traceability headers.
7. **Code→design sync:** inventory → tree-sitter extractors (Java, TS/JS) → clustering → LLM labelling → DI/interface resolution pass → foundation-layer isolation → reconcile patch → drift report → cross-project linking (per approach above). Chat UI (streaming, tool-call diff cards) is built alongside phases 3–5.
8. **Manual demo walkthrough of the scenarios** (see Verification).

## Verification (manual smoke checks — no automated tests)
- Spike: Archify `validate` passes and HTML renders for a sample IR.
- Guardrails by hand: an oversize message is rejected, a cache hit shows no new tokens in the usage meter, a malformed LLM response is retried once only.
- Reverse-engineering sample repos: (1) Spring (`IUserRepository` + `@Repository PostgresUserRepository`) and NestJS (`useClass`) → edge resolves to the implementation; naming-only match is low confidence. (2) Repo where every package imports `utils/` → no star topology, single foundation band. (3) Two-project fixture (TS `fetch(`/api/users/${id}`)` ↔ Java `@GetMapping("/api/users/{id}")`) → one dashed edge with both evidence locations; a non-matching URL appears as "unresolved external call".
- Scenario walkthrough (real LLM, done by hand): (a) capture intent for a sample "todo app" → doc + diagram; (b) chat "add OAuth2 login" → Auth component + link appears in diagram, REQ added; (c) chat "add Redis caching" → cache node appears; (d) add `redis` dependency/ code in generated project and Sync → diagram matches; (e) undo restores previous diagram.
- Token budget check: scenario (b) and (c) each stay under a target (e.g. <2k prompt + <500 completion tokens), read from the usage meter.

## Tech stack (decided — details in tech-stack.md)
Spring Boot (Java 17) backend, React + Vite + Tailwind frontend, Archify and tree-sitter run as Node subprocesses, project files in one S3 bucket, SQLite for usage/settings, single admin login, deployed as one Docker container on a single AWS Lightsail/EC2 instance.

## Open questions for you
0. ~~Number of users~~ — decided: single admin user.
1. ~~Stack choice~~ — decided, see tech-stack.md.
2. Language/framework of the *generated* apps in MVP (e.g. Spring Boot + React only?).
3. ~~Archify as runtime dependency~~ — decided: Node 22 bundled in the container, Archify called as a subprocess.
4. Code-change trigger: manual "sync" button acceptable for MVP?
5. OpenAI model preference / budget cap per project.

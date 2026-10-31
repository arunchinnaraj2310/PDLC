# AGENTS.md

This file provides guidance to Codex (claude.ai/code) when working with code in this repository.

## Repository status
Greenfield: the repo currently contains only [PLAN.md](PLAN.md) (scope, architecture, phases) and [tech-stack.md](tech-stack.md) (decided stack). There is no source code, build, or test setup yet — add build/run commands here once the project is scaffolded. Treat the two docs as the source of truth and keep them consistent with each other when changing either.

## What is being built
A single-admin PDLC assistant: an OpenAI-backed chatbot that captures product intent and produces Requirements, Design (with diagrams) and generated code, with **living traceability** (requirement or code changes automatically update the design diagram). It is a POC: minimal infrastructure, no automated tests, no observability (these are explicitly out of scope).

Stack (see tech-stack.md): Spring Boot (Java 17, Maven) backend; React + JavaScript + Vite + Tailwind frontend, built into Spring Boot's `static/` so it ships as one jar; Archify and a tree-sitter extractor run as Node 22 subprocesses; one S3 bucket for project files; SQLite for usage ledger/settings; single admin login from env config; one Docker container on one Lightsail/EC2 instance.

## Architecture (big picture)
- **Markdown files are the model.** Each project is an S3 prefix of md files with YAML front matter and stable IDs (`REQ-###`, `CMP-###`, ADRs), plus `diagrams/`, `src/`, and internal `.pdlc/` (chats, `facts.json`, usage). The LLM edits **by section/ID via patches**, never by rewriting documents. Undo = S3 object versions + `changelog.md` (not git). Backend works on a temp scratch dir per request because the Node tools need real files, then syncs back.
- **Diagrams are derived, never hand-edited or drawn by the LLM.** Java maps the md model → Archify typed JSON IR → `archify validate` (a guardrail) → render HTML. The LLM only emits small patches.
- **All LLM calls go through one LLM Gateway** that enforces the token guardrails in one place: schema-enforced output with retry budget 1, context slicing (affected IDs + 1-hop), cheap-model intent router vs stronger codegen model, response cache, per-request/session/project/global budgets with pre-flight estimate, usage ledger. Never bypass it.
- **Deterministic-first reverse engineering (code → design):** inventory/manifests → tree-sitter extraction to `facts.json` (nodes/edges with `file:line` evidence) → clustering into ~8–15 components → LLM labelling of compact facts only (cached by facts hash; user-edited names are pinned) → reconcile against `11-building-blocks.md` as an Apply/Reject patch → drift report. Includes: interface→implementation/DI resolution (DI registration > `implements` > naming convention, with confidence), `utils/shared` clusters isolated as a foundation band instead of per-component edges, and cross-project frontend↔backend linking by matching normalized URL templates (dashed, low/medium-confidence edges). LLM never reads whole source files.
- **Chat flow:** streaming SSE chat; doc changes happen through tool calls (`propose_doc_patch`, `update_diagram`, `generate_code`) shown as inline Apply/Reject diff cards; nothing is written until the admin applies.
- **Traceability:** REQ ↔ CMP ↔ file links let design/code impact be computed without the LLM.

## Conventions specific to this project
- Project content lives in S3 (`storage.mode=local|s3` flag for local dev), not in the DB; SQLite holds only usage/budgets/conversation index/settings.
- Keep infra minimal; do not introduce Postgres, Redis, queues, ECS, Cognito, CI/CD or IaC without the user asking (tech-stack.md §8 lists what was deliberately left out).
- Phase order is in PLAN.md; phase 0 is a spike to confirm Archify's IR schema/CLI contract and OpenAI structured output before building on them (fallback diagram engine: Mermaid).
- Open decisions are tracked at the bottom of PLAN.md and tech-stack.md; update them when decided.

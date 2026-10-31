# PDLC Assistant — Tech Stack (POC, single admin)

Derived from [PLAN.md](PLAN.md). Principle: **fewest moving parts that work** for one admin user. Anything not listed here is deliberately left out.

## 1. Summary

| Layer | Choice |
|---|---|
| Backend | Java 17, Spring Boot 4.0.x (Maven) — start.spring.io no longer offers 3.x |
| Frontend | React + JavaScript (JSX) + Vite |
| Styling | Tailwind CSS |
| Diagrams | Archify (Node CLI, called as a subprocess) |
| LLM | OpenAI API via official Java SDK |
| Reverse engineering | tree-sitter (Node script), manifest parsers |
| Project files (md, diagrams, code) | One S3 bucket |
| App data (usage ledger, chat index, settings) | SQLite file (no database server) |
| Auth | Single admin, username/password from env/Secrets (Spring Security) |
| Hosting | One AWS Lightsail (or EC2) instance running Docker |

## 2. Backend (Spring Boot)
- Spring Web + `SseEmitter` for streaming chat.
- `openai-java` SDK inside one **LLM Gateway** class (model routing, JSON-schema output, token cap, cache, usage ledger).
- Jackson (JSON), `zjsonpatch` (JSON Patch), `commonmark-java` + `snakeyaml` (md + front matter).
- Spring Security: one admin account, credentials from environment variables, HTTP-only session cookie. No user table, no registration.
- Spring Data JPA + SQLite (`sqlite-jdbc`, Hibernate community dialect) — or plain `JdbcTemplate`; schema created at startup (no migration tool).
- Response cache and rate limiting: in-process **Caffeine**. No Redis.

## 3. Frontend (React + Vite + Tailwind)
- React, plain JavaScript (JSX, no TypeScript), Vite, npm.
- Tailwind CSS (+ typography plugin for rendered markdown).
- Chat streaming with `fetch` + `ReadableStream`; `react-markdown` + `remark-gfm` for markdown/code blocks.
- Diff cards (Apply/Reject) with the `diff` package.
- Plain `fetch` + React state; no state-management library.
- Diagram pane: `<iframe>` showing the Archify HTML served by the backend.
- Production build is copied into Spring Boot's `static/` folder, so the app is **one deployable jar** — no separate frontend hosting or CDN.

## 4. Diagrams — Archify
- Node 22 LTS installed in the same container; Spring Boot runs `node archify.mjs validate|deliver|compare` via `ProcessBuilder` in a temp dir with a timeout.
- Archify is vendored at a pinned version under `tools/archify/`.
- Java maps the md model → Archify IR; `validate` runs before every render.
- Fallback if the phase-0 spike rejects Archify: Mermaid.

## 5. Reverse engineering (code → design)
- **tree-sitter** (`tree-sitter`, `tree-sitter-java`, `tree-sitter-typescript` npm packages) in a single Node script, `tools/extract/`, run by Spring Boot as a subprocess (same pattern as Archify). Outputs `facts.json`.
- Manifests/config: Jackson (`package.json`), `maven-model` (`pom.xml`), SnakeYAML (`application.yml`, `docker-compose.yml`).
- Dependency→component rules: one YAML file in the repo.
- Interface/DI resolution, shared-layer isolation, cross-project URL matching, clustering: plain Java over `facts.json` (no graph library needed at this scale).
- Incremental sync: SHA-256 content hashes stored in `facts.json` (no git library).
- Repo input: upload a zip or point to a local/uploaded folder. Scans run synchronously with a progress message in chat (no job queue).

## 6. Storage
### S3 (single bucket)
- Versioning on, encryption default (SSE-S3), public access blocked.
- Layout: `projects/{projectId}/docs/*.md`, `diagrams/*`, `src/**`, `.pdlc/chats/*.md`, `.pdlc/facts.json`.
- **Undo:** S3 object versions + `changelog.md` (no git).
- Backend uses AWS SDK for Java v2; works on a temp scratch dir per request (needed by Archify/tree-sitter), then syncs back. Access via the instance's IAM role — no keys in config.

### SQLite (local file on the instance)
- Holds only: token usage ledger, budgets, conversation index, settings.
- Single file on the instance's disk; nightly copy to the same S3 bucket (`backups/`) with a cron one-liner. Everything else (project content) is in S3, so losing the DB loses only usage history.

## 7. Deployment (minimal AWS)
```
Browser ──HTTPS──► Caddy (auto TLS) ──► Docker container: Spring Boot jar (+ React build, Node 22, Archify, extractor)
                                              ├─► S3 bucket (project files)   [instance IAM role]
                                              ├─► SQLite file (mounted volume)
                                              └─► OpenAI API
```
- **One Lightsail instance** (2 GB RAM is enough; or a `t3.small` EC2) with Docker + Docker Compose (two services: `app`, `caddy`).
- Domain/HTTPS: a DNS A record plus Caddy's automatic Let's Encrypt certificate. (Alternative: restrict access by IP / Tailscale and skip the domain.)
- Secrets: OpenAI key and admin password in a `.env` file on the instance (permissions `600`); not in the repo.
- Deploy: `git pull && docker compose up -d --build` on the instance (or build locally and `docker save | ssh docker load`). No registry, no CI/CD pipeline, no infrastructure-as-code.
- One-time AWS setup (console or a short CLI script): create the S3 bucket, an IAM role/policy limited to that bucket, launch the instance.
- Local development: `mvn spring-boot:run` + `npm run dev`, with a local folder instead of S3 selected by a config flag (`storage.mode=local|s3`).

## 8. Explicitly left out (POC)
- Automated tests and test tooling (manual smoke checks only).
- Observability: no metrics, tracing, dashboards or alarms — Spring Boot's default console logs only (`docker compose logs`).
- RDS/Postgres, ElastiCache/Redis, SQS, ECS/Fargate, ALB, CloudFront, Cognito, WAF, ECR, Terraform/CDK, Secrets Manager, CI/CD pipeline.
- Multi-user concerns (roles, accounts, user tables).

Upgrade path if ever needed: SQLite→RDS Postgres, local container→ECS Fargate, `.env`→Secrets Manager.

## 9. Mapping to PLAN.md phases
| Phase | Stack pieces |
|---|---|
| 0 Spike | Node + Archify CLI; OpenAI structured output from the Java SDK |
| 1 Project md model | commonmark-java, SnakeYAML, S3 versioning, JSON Patch |
| 2 LLM gateway | openai-java, token estimate, Caffeine cache, SQLite ledger |
| 3–5 Capture, design, propagation | Spring Web + SSE, React chat, Archify subprocess, diff cards |
| 6 Code generation | Per-component LLM calls, write to S3 `src/` |
| 7 Code→design | Node tree-sitter script, manifest parsers, plain-Java resolution/clustering/linking |
| 8 Demo scenarios | Manual walkthrough (no automated tests) |

## 10. Decisions to confirm
1. Lightsail vs plain EC2 (recommended: Lightsail — fixed price, simplest).
2. Domain + Caddy TLS vs IP-restricted access.
3. SQLite vs storing the ledger as md/JSON files in S3 (recommended: SQLite; the file approach removes the DB entirely if you prefer).

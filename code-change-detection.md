# Code change detection and diagram update

How a change in the code is detected and turned into an updated design diagram. Almost all of it is deterministic. The LLM is only used for naming.

## 1. Detection (`CodeWatchService`)

- Every `pdlc.code.poll-ms`, the watcher computes a cheap fingerprint of each synced source folder: file count, total size and newest modification time.
- When the fingerprint changes, it waits one more poll. If the fingerprint is stable, you've stopped saving and it proceeds. This debounces half-finished edits.
- It then calls `CodeSyncService.syncAndApply`, which processes only the changed files and applies the result immediately. It also bumps `version` in `.pdlc/autosync.json`.

## 2. Code to facts (deterministic)

1. **Inventory** (`CodeInventoryService`)
   - It walks the folder and skips `node_modules`, `target` and tests.
   - It SHA-256 hashes each source file, so only files whose hash changed go to the next step.
   - It matches manifests (`pom.xml`, `package.json`, `docker-compose` and so on) against `code-rules.yml` to find external systems, such as Redis or Postgres. Each one comes with file:line evidence.
2. **Extraction** (`CodeExtractorRunner`)
   - A Node subprocess (`tools/extract/extract.mjs`) parses each changed file with tree-sitter.
   - It returns imports, types, annotations, routes, entities and outbound URLs, each with a line number.
   - These facts are cached in `.pdlc/facts.json`.
3. **Graph** (`CodeGraphService`)
   - It resolves imports to files.
   - It clusters files into components by package or folder. Generated files are clustered as `cmp:<CMP-id>`, so they map straight back to the designed component.
   - It aggregates imports into caller→callee edges and decides each component's layer (backend, frontend, security).
4. **Labelling** (`CodeLabelService`)
   - This is the only LLM step. It gets a few lines of facts per cluster and returns a name, a responsibility, and which REQs the cluster satisfies.
   - The result is cached in `.pdlc/labels.json` by a hash of facts plus REQs. An unchanged re-sync makes no model call.

## 3. Facts to design (`CodeSyncService.reconcile`)

- Clusters are matched to existing components in `11-building-blocks.md` through the `code:` attribute. Externals use `ext:<Name>`. A designed component with the same name is adopted and gets a `code:` attribute.
- The result is a set of add, update and remove patch ops:
  - A new package becomes a new CMP.
  - A new import becomes a new `links` entry.
  - A deleted package removes its CMP and any links to it.
- Links designed by hand are never dropped. Only links to removed components are cleaned up.
- If code for a designed component disappears, the component stays, because the design is intent. It shows up in the drift report as "designed but no code".
- The drift report also lists code with no REQ and any unsatisfied REQ.

## 4. Apply and redraw

- The patch set goes through `ProposalBuilder` and `ProjectService.previewAll/applyAll`:
  - It validates the combined REQ and CMP state.
  - It backs up the files, writes them, bumps `version` and appends to `changelog.md`.
  - Undo restores the backup.
- The diagram is derived from the markdown, never stored as a source of truth:
  - `DiagramService.render` runs `ArchitectureIrMapper`, which turns the markdown into Archify IR. Layers become rows, and links become connections.
  - `ArchifyRunner` renders the IR to `diagrams/architecture.html`.
  - `GET /diagram` re-renders on every call, so it can't be stale.
- On the frontend, `useCodeSync` polls `/code/status` every 5 seconds. When the version changes, it reloads the diagram and the file tree.

## In short

```
edit file → watcher sees fingerprint change → hash diff → tree-sitter facts
→ cluster + edges → (LLM names new clusters) → reconcile vs building-blocks.md
→ patch applied (backup + changelog) → md changes → diagram re-rendered
```

The model is never trusted with structure. It can name components but can't invent links.

## Known limits

- Same-package Java references without an import aren't seen.
- Dynamic URLs and reflection are invisible.
- Cluster keys can change when a folder's share of the module crosses the split threshold.

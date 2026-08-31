# distributed-cache-engine — Distributed in-memory key-value cache

Motor de caché clave-valor distribuido capaz de replicar datos de forma asíncrona entre múltiples nodos.

> Scaffold created from `projects/_template/` on 2026-08-31. **No code yet** — this is
> the architectural skeleton only. The GitHub profile README links point to
> `github.com/nedder3`, not a real repo. See "State" before starting.

## Context
- **Goal**: Motor de caché clave-valor distribuido capaz de replicar datos de forma asíncrona entre múltiples nodos.
- **Stack**: Java, gRPC, TCP Sockets, ConcurrentHashMap, Evicción LRU / LFU, replicación activa-activa.
- **License**: MIT (vault default; same as sibling projects).

## Vault structure
- `projects/distributed-cache-engine/` — mirror of the GitHub repo `nedder3/distributed-cache-engine` (canonical source).
- `graphify-out/` is auto-generated: exclude from the Obsidian graph and do NOT edit by hand.

## Canonical documentation (this project)
- `README.md` — Standard Readme compliant.
- `CHANGELOG.md` — Keep a Changelog, maintained by hand per significant release/commit.
- `CONTRIBUTING.md` — conventional-commit rules with scope `distributed-cache-engine`, TDD, JSDoc.
- `docs/CODE-DOCUMENTATION.md` — JSDoc/TSDoc standard.
- `diagramas/` — mechanism diagrams (mermaid `.mmd`), no loose wikilinks.

## Commit & code conventions (Cy)
- Conventional Commits with scope `distributed-cache-engine`: `feat(distributed-cache-engine): ...`, `fix(distributed-cache-engine): ...`.
- TDD: tests in `src/` before declaring done. Verify with `hermes verify`.
- Graphify: `/graphify .` to map the repo (output in `graphify-out/`).

## Architecture (summary)
Implementa estrategias de desalojo avanzadas (LRU, LFU) y consistencia eventual mediante replicación activa-activa orientada a redes internas confiables. MVP single-node (ConcurrentHashMap + LRU/LFU) es días; el salto a distribuido (gRPC + replicación) es meses.

## What NOT to do
- Do not edit `graphify-out/` by hand.
- Do not introduce frameworks not listed in the stack without a brief.

## Briefing & decisions (cross-project)
- All briefs live in `decisions/` at the vault root, never inside this project. Filename
  carries the project slug: `.{profile}-{epic}-{type}-brief-distributed-cache-engine.txt`.
- Run profiles via `hermes -p <profile>` (real SOUL + skills), never roleplay.
  Series, not parallel (anti-429 / upstream 503).
- Wake-up: every background launch uses `terminal(background=true, notify_on_complete=true)`.
- NO ASumas, VERIFICA: verify with real commands before claiming done.
- Kanban required: every task MUST have a card on `hermes kanban --board distributed-cache-engine`.
- Delegation flow (series): orchestrator writes brief → launches `hermes -p <profile>`
  one at a time → verifies → Gantz gate → Dva deploy. Never roleplay via `delegate_task`.

## State (scaffold created 2026-08-31)
- No code yet. This folder is the architectural skeleton only.
- GitHub repo `nedder3/distributed-cache-engine` created + initial scaffold pushed (main).
- Build tooling (Maven/Gradle) and module layout TBD in the first brief.

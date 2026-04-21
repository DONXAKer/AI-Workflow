# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AI-powered development pipeline platform (monorepo):
- **`workflow-core/`** — Java Spring Boot backend (REST API + WebSocket)
- **`workflow-ui/`** — React/TypeScript frontend
- **`config/`** — shared pipeline YAML configs
- **`docs/`** — documentation

The platform automates software development workflows: requirement intake → analysis → task creation → code generation → PR/MR creation → CI monitoring, with human approval gates at each step.

## Environment Setup

Copy `.env.example` to `.env` and fill in API keys (OpenRouter, YouTrack, GitHub/GitLab). The backend reads these at startup.

## Java Backend (`workflow-core`)

```bash
cd workflow-core
gradle bootRun       # starts on port 8020
gradle build         # 118 tests across 21 classes, including 3 live-OpenRouter ITs
gradle test --tests 'com.workflow.SomeTest'  # run a single test class
gradle test --tests '*IT'                     # just integration tests (need OPENROUTER_API_KEY)
```

No gradle on PATH / no JDK locally? Build in docker:
```bash
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "//c/path/to/project:/app" \
  -v gradle-cache:/home/gradle/.gradle \
  -e OPENROUTER_API_KEY="<key>" \
  -w /app gradle:8.12-jdk21-alpine \
  gradle build --no-daemon
```
Cyrillic paths trip docker's path-conversion on Windows/MSYS — copy the workspace into an ASCII-only temp dir first and mount that.

Integration tests (`*IT`) are gated by `@EnabledIfEnvironmentVariable(OPENROUTER_API_KEY)` so the default test run stays hermetic. Set `webEnvironment = WebEnvironment.MOCK` (not `NONE`) in `@SpringBootTest` ITs — `SecurityConfig.filterChain(HttpSecurity)` needs a servlet context to autowire.

H2 console available at `http://localhost:8020/h2-console` (JDBC URL: `jdbc:h2:file:./workflow-db`). Database uses `ddl-auto: update` — schema auto-migrates from entities.

Spring Boot 3.4.4 / Java 21. Key packages: `api/` (REST + WebSocket), `blocks/`, `core/` (PipelineRunner, JPA entities), `core/expr/` (PathResolver + StringInterpolator for `${block.field}` templates), `integrations/`, `knowledge/`, `llm/` (OpenRouter client + tool-use loop), `llm/tooluse/` (ToolDefinition/Call/Result + request/response records), `tools/` (native Tool impls + PathScope + Deny/Allow lists + DefaultToolExecutor + ToolCallAudit), `config/`, `skills/` (legacy AgentProfile-bound layer — distinct from `tools/`), `model/` (IntegrationConfig + AgentProfile JPA entities), `cli/` (Spring Shell commands), `project/` (Project entity with `workingDir` as PathScope root).

Dual approval gate modes: `WebSocketApprovalGate` (real-time via `/ws`) or `CliApprovalGate` — controlled by `workflow.mode: cli|gui` in `application.yaml`.

### Architecture

**Block-based DAG execution** (`com.workflow`):
- `core/PipelineRunner.java` — block registry, topological sort, execution loop with condition/loopback support
- `blocks/Block.java` — interface (implement `run(input, config, run) -> Map`)
- `core/PipelineRun.java` — JPA entity: run state, completedBlocks, loopIterations, loopHistory
- `core/BlockOutput.java` — per-block JSON output persisted to H2
- `core/ApprovalGate.java` — interface with WebSocket and CLI implementations
- `config/` — POJO config classes deserialized from pipeline YAML

**Blocks (29 total).** Legacy blocks (the original 13):
- `analysis` — analyses requirements, returns affected_components, technical_approach, complexity
- `clarification` — interactive Q&A to refine requirements (conditional: skipped if complexity=low)
- `code_generation` — generates file changes per task, branch name, commit message
- `verify` — structural field checks + LLM quality evaluation; triggers loopback on failure
- `youtrack_input` / `youtrack_tasks_input` / `youtrack_tasks` — YouTrack read + subtask creation
- `git_branch_input` — reads diff from an existing git branch
- `gitlab_mr` / `gitlab_ci` — GitLab MR + pipeline monitoring
- `github_pr` / `github_actions` — GitHub PR + Actions workflow
- `mr_input` — reads an existing MR/PR
- plus `analysis`, `business_intake`, `task_input`, `ai_review`, `run_tests`, `test_generation`, `build`, `deploy`, `rollback`, `vcs_merge`, `verify_prod`, `release_notes`

Phase 1 additions (see `docs/phase1-plan.md`):
- `agent_with_tools` — LLM tool-use loop with native tools (M2.6)
- `task_md_input` — parses task.md into structured sections + heuristic flags (M3.3)
- `shell_exec` — runs a shell command as a pipeline step, no LLM (M3.4)
- `claude_code_shell` — shells out to local `claude -p` (Layer 3 / MCP / Max-subscription flows) (M3.5)
- `http_get` — HTTP GET with optional JSON parse (M5)

### Tool-use (Phase 1)

`agent_with_tools` drives an agentic loop via `LlmClient.completeWithTools`. Each iteration posts messages+tools to OpenRouter's OpenAI-compatible endpoint; the loop ends on `END_TURN` / `MAX_TOKENS` / `MAX_ITERATIONS` / `BUDGET_EXCEEDED`.

**Native tools** (`com.workflow.tools`, copied from Claude Code built-ins so SKILL.md files port 1:1): `Read`, `Write`, `Edit`, `Glob`, `Grep`, `Bash`. All filesystem tools resolve paths through `PathScope` which rejects anything outside `Project.workingDir` after canonicalization (including symlink escapes).

**Security floor** (`DenyList`, cannot be widened by block config):
- Writes to `.env*`, `*.pem`, `*.key`, SSH private keys — rejected.
- Bash: `git push --force`, `git reset --hard`, `rm -rf`, `chmod -R`, `| sh`, `| bash` — rejected.

**Per-block bash allowlist** — Claude-Code-style `Bash(git *)` patterns in block YAML. Empty allowlist = no bash at all.

**Audit**: every API round-trip writes an `LlmCall` row (with `iteration` 1..N and `toolCallsMadeJson`); every tool invocation writes a `ToolCallAudit` row with `(runId, blockId, iteration, toolName, inputJson, outputText, isError, durationMs)`.

### YAML interpolation (Phase 1)

Two forms, both resolving against completed block outputs:
- `${block.field}` / `${block.field.nested}` — string interpolation inside YAML values (StringInterpolator)
- `${input.key}` — reads the current block's input map (e.g. `${input.task_file}`)
- `$.block.field OP value` — legacy expression form for `condition:` and `inject_context:` (PipelineRunner)

Fail-loud: missing paths throw `PathNotFoundException` — they never render as empty string.

**Key behaviors:**
- `approval: true` in YAML pauses for user confirmation via WebSocket (GUI mode) or terminal (CLI mode)
- `condition: "$.block_id.field OPERATOR value"` — skip block if condition is false
- `verify` block with `on_fail.action: loopback` — resets completed blocks back to target, retries with feedback
- `on_failure.action: loopback` on CI blocks — retries code generation after CI failure
- `entry_points:` in YAML — named shortcuts; POST `/api/runs` with `entryPointId` resolves fromBlock + injections automatically
- Loopback history tracked in `loop_iterations` and `loop_history_json` on `PipelineRun`

**Adding a new block type:**
1. Create `src/main/java/com/workflow/blocks/MyBlock.java` implementing `Block`
2. Annotate with `@Component` — auto-registered in `PipelineRunner.buildRegistry()`
3. Reference in YAML as `block: my_block_name`

### Config Structure (`config/pipeline.example.yaml`)
```yaml
entry_points:
  - id: from_scratch
    name: "New requirement"
    from_block: youtrack_input
    requires_input: requirement

  - id: tasks_exist
    name: "Tasks already in YouTrack"
    from_block: codegen
    requires_input: youtrack_issue
    inject:
      - { block_id: analysis, source: empty }
      - { block_id: tasks, source: youtrack }
    auto_detect: youtrack_subtasks

integrations:
  youtrack: { url, token, project }
  gitlab: { url, token, project_id }
  github: { token, owner, repo }
knowledge_base:
  sources:
    - type: git | files
      ...
pipeline:
  - id: block_id
    block: block_type_name
    depends_on: [other_block_id]
    approval: true|false
    condition: "$.analysis.estimated_complexity != 'low'"
    agent: { model, temperature, ... }
    verify:
      subject: block_id
      checks:
        - { field: affected_components, rule: min_items, value: 1 }
      llm_check:
        enabled: true
        prompt: "Rate the analysis 0-10..."
        min_score: 7
      on_fail:
        action: loopback
        target: analysis
        max_iterations: 2
        inject_context:
          feedback: "$.verify_analysis.issues"
    on_failure:
      action: loopback
      target: codegen
      max_iterations: 2
      failed_statuses: [failure, failed, timeout]
```

### REST API
- `POST /api/runs` — start a run (`configPath`, `requirement`, `fromBlock`, `entryPointId`, `injectedOutputs`)
- `GET /api/runs` — list runs (pagination, filters)
- `GET /api/runs/stats` — run statistics
- `GET /api/runs/{runId}` — run detail
- `POST /api/runs/{runId}/approval` — resolve approval gate
- `POST /api/runs/{runId}/cancel` — cancel run
- `POST /api/runs/detect` — auto-detect entry point
- `GET /api/pipelines` — list pipeline configs
- `GET /api/pipelines/entry-points?configPath=...` — list entry points for a config
- `GET/POST/PUT/DELETE /api/integrations` — integration CRUD + `POST /{id}/test`
- `GET/POST/PUT/DELETE /api/agent-profiles` — agent profile CRUD + `GET /skills`
- `WS /ws` — STOMP over SockJS; subscribe to `/topic/runs/{runId}` or `/topic/runs`

## React Frontend (`workflow-ui`)

```bash
cd workflow-ui
npm install
npm run dev     # port 5120, proxies /api and /ws to localhost:8020
npm run build   # tsc && vite build → dist/
```

React 18 + TypeScript strict + Tailwind CSS. STOMP/SockJS WebSocket for live updates. State: `RunsContext.tsx` (global runs), custom hooks in `hooks/`. Pages in `pages/`, reusable components in `components/`.

## Docker

```bash
docker-compose up --build   # starts both services (backend :8020, frontend :5120→80)
```

Run state is persisted in a Docker volume (`workflow-state`).

## Wiki
При любом упоминании изменений в этом проекте — обновлять /Users/home/Wiki/projects/AI-Workflow.md

## Phase 1 (branch `feat/warcard-pipeline-phase1`)

Design doc: `docs/phase1-plan.md` (locked grill session 2026-04-20).
Operator runbook for the bundled `config/feature.yaml`: `docs/running-feature-pipeline.md`.
GUI quickstart: `docs/gui-quickstart.md`.

Milestones M1 (tool-use core) → M5 (http_get self-feature) all merged on the branch. Acceptance tests (real-repo run producing a git commit) are user-driven — the pipeline is ready but the operator picks the target repo and task. Tasks live in `tasks/active/` + `tasks/done/` at repo root, `<FEAT-ID>_<slug>.md` convention.

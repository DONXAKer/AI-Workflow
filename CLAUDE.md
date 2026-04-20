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
gradle test          # no tests exist yet; JUnit 5 infrastructure is in place
gradle build
gradle test --tests 'com.workflow.SomeTest'  # run a single test class (when tests exist)
```

H2 console available at `http://localhost:8020/h2-console` (JDBC URL: `jdbc:h2:file:./workflow-db`). Database uses `ddl-auto: update` — schema auto-migrates from entities.

Spring Boot 3.4.4 / Java 21. Key packages: `api/` (REST + WebSocket), `blocks/`, `core/` (PipelineRunner, JPA entities), `integrations/`, `knowledge/`, `llm/`, `config/`, `skills/` (tool abstractions for LLM agents), `model/` (IntegrationConfig + AgentProfile JPA entities), `cli/` (Spring Shell commands).

Dual approval gate modes: `WebSocketApprovalGate` (real-time via `/ws`) or `CliApprovalGate` — controlled by `workflow.mode: cli|gui` in `application.yaml`.

### Architecture

**Block-based DAG execution** (`com.workflow`):
- `core/PipelineRunner.java` — block registry, topological sort, execution loop with condition/loopback support
- `blocks/Block.java` — interface (implement `run(input, config, run) -> Map`)
- `core/PipelineRun.java` — JPA entity: run state, completedBlocks, loopIterations, loopHistory
- `core/BlockOutput.java` — per-block JSON output persisted to H2
- `core/ApprovalGate.java` — interface with WebSocket and CLI implementations
- `config/` — POJO config classes deserialized from pipeline YAML

**Blocks (13 total):**
- `analysis` — analyses requirements, returns affected_components, technical_approach, complexity
- `clarification` — interactive Q&A to refine requirements (conditional: skipped if complexity=low)
- `code_generation` — generates file changes per task, branch name, commit message
- `verify` — structural field checks + LLM quality evaluation; triggers loopback on failure
- `youtrack_input` — reads requirement from YouTrack issue
- `youtrack_task_creation` — creates subtasks in YouTrack
- `youtrack_tasks_input` — reads existing YouTrack subtasks
- `git_branch_input` — reads diff from an existing git branch
- `gitlab_mr` — creates GitLab merge request
- `gitlab_ci` — monitors CI pipeline, waits for completion
- `github_pr` — creates GitHub pull request
- `github_actions` — monitors GitHub Actions workflow
- `mr_input` — reads an existing MR/PR

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

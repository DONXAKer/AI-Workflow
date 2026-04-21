# Running the feature pipeline

Runbook for operators driving the `feature` pipeline shipped in
`workflow-core/src/main/resources/config/feature.yaml`. The pipeline is the
native replacement for the Claude Code `/feature` skill — see
`docs/phase1-plan.md` for design rationale.

## One-time project setup

1. **Create a Project row** pointing at your repo's absolute path. REST:
   ```
   POST /api/projects
   {
     "slug": "myproject",
     "displayName": "My Project",
     "workingDir": "/path/to/repo"
   }
   ```
   Or use `scripts/bootstrap-project.ps1`. `Project.workingDir` is the
   filesystem root every native tool is scoped to (see `PathScope`).

2. **Drop the pipeline YAML into the project:**
   ```
   <working_dir>/.ai-workflow/pipelines/feature.yaml
   ```
   Or keep using the one shipped in `workflow-core/config/`. Point
   `workflow.config-dir` at whichever location you prefer.

3. **Seed OpenRouter credentials** via `POST /api/integrations` or
   `OPENROUTER_API_KEY` env var.

## Per-run inputs

The pipeline expects one input on the run:

| Input | Example | Source |
|---|---|---|
| `requirement` | `/project/tasks/active/FEAT-001_my-feature.md` | Absolute path to the task.md file. Parsed by `task_md_input`. |

The `X-Project-Slug` header on `POST /api/runs` pins the project so
`Project.workingDir` is picked up by both `agent_with_tools` and
`shell_exec` blocks as their default cwd.

## Starting a run — GUI

1. Open `http://localhost:5120` (or `http://localhost:8020` if using the backend directly).
2. Navigate to **Пайплайны** in the sidebar.
3. Select `feature` from the pipeline list.
4. Select entry point **"Implement a task.md ticket"**.
5. Paste the absolute path to the task file into the **Требование** field.
6. Click **Запустить**.
7. You are redirected to the run page — watch block progress in real time.

## Starting a run — CLI (`wf`)

If the project has `wf.ps1` at its root (see `tools/wf-cli/`):

```powershell
$env:WF_PASSWORD = "your-password"
.\wf run FEAT-001          # resolves task file from tasks/active/
.\wf status <runId>
.\wf approve <runId>
```

## Starting a run — REST

```
POST /api/runs
X-Project-Slug: myproject

{
  "configPath": "/project/.ai-workflow/pipelines/feature.yaml",
  "entryPointId": "implement",
  "requirement": "/project/tasks/active/FEAT-001_my-feature.md"
}
```

## What each block does

1. **`task_md`** parses the ticket markdown into structured sections
   (`as_is`, `to_be`, `acceptance`) and sets heuristic flags
   (`needs_server`, `needs_client`, `needs_contract_change`, ...).
2. **`impl`** gives the LLM access to your repo via six native tools
   (Read/Write/Edit/Glob/Grep/Bash) plus a small bash allowlist.
   System/user prompts interpolate the parsed task.
3. **`build`** runs the build command. `allow_nonzero_exit: true` so that
   failures reach `verify_build` instead of crashing the block.
4. **`verify_build`** checks `build.success == true`. On failure it loops
   back to `impl`, injecting `build_stderr` and `build_exit` so the agent
   sees why the compile failed. Max 3 iterations.
5. **`tests`** runs the test command the same way.
6. **`verify_tests`** loops back to `impl` on failure with
   `test_failures` injected. Max 3 iterations.
7. **`commit`** runs `git add -A && git commit -m "..."` with the feat_id
   and task title templated in. **No push** — local commit only.

## Loopback feedback

On any iteration where the verify block loops back, `agent_with_tools`
auto-appends the captured diagnostics to the user message:

```
---
Previous attempt (iteration 2) did not pass verification.
Address the issues below before retrying:
- build_stderr: error: no member named 'Foo'
- build_exit: 2
```

## Audit trail

Every run persists:

- `llm_call` rows — one per API round-trip, `iteration` field 1..N.
- `tool_call_audit` rows — one per `Tool.execute`, correlatable via
  `(run_id, block_id, iteration)`.
- `block_output` rows — one per block, plus `_loopback_<target>`
  synthetic outputs carrying verify issues across iterations.

Query examples in the H2 console or via `/api/runs/{id}/llm-calls`.

## When the pipeline doesn't fit

- **MCP / Blueprint generation / Max-subscription flows** — use
  `claude_code_shell` blocks instead of `agent_with_tools`. Same
  cwd/interpolation semantics, different execution path.
- **Non-standard tickets** (ADRs, spikes, docs-only) — write a custom
  pipeline YAML. Everything here is a reference, not a mandate.

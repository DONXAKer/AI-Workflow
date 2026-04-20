# Running the feature pipeline

Runbook for operators driving the `feature` pipeline shipped in
`workflow-core/src/main/resources/config/feature.yaml`. The pipeline is the
native replacement for the Claude Code `/feature` skill — see
`docs/warcard-pipeline-phase1-plan.md` for design rationale.

## One-time project setup

1. **Create a Project row** pointing at your repo's absolute path. REST:
   ```
   POST /api/projects
   {
     "slug": "warcard",
     "displayName": "WarCard",
     "workingDir": "D:/WarCard"
   }
   ```
   Or directly via H2 console — `Project.workingDir` is the filesystem root
   every native tool is scoped to (see `PathScope`).

2. **Drop the pipeline YAML into the project:**
   ```
   <working_dir>/.ai-workflow/pipelines/feature.yaml
   ```
   Or keep using the one shipped in `workflow-core/config/`. Point
   `workflow.config-dir` at whichever location you prefer.

3. **Seed OpenRouter credentials** via `POST /api/integrations` or
   `OPENROUTER_API_KEY` env var.

## Per-run inputs

The pipeline expects three inputs on the run:

| Input | Example | Source |
|---|---|---|
| `task_file` | `tasks/active/FIX-BOOT-009_matchmaking-ustruct.md` | Absolute or relative to `working_dir`. Parsed by `task_md_input`. |
| `build_command` | `cmd.exe /c Build.bat WarCardEditor Win64 Development` | Shell command. Exit 0 passes `verify_build`. |
| `test_command` | `cmd.exe /c RunValidator.bat` | Shell command. Exit 0 passes `verify_tests`. |

The `X-Project-Slug` header on `POST /api/runs` pins the project so
`Project.workingDir` is picked up by both `agent_with_tools` and
`shell_exec` blocks as their default cwd.

## Starting a run

```
POST /api/runs
X-Project-Slug: warcard

{
  "configPath": "config/feature.yaml",
  "entryPointId": "implement",
  "injectedOutputs": {
    "_run_input": {
      "task_file": "tasks/active/FIX-BOOT-009_matchmaking-ustruct.md",
      "build_command": "cmd.exe /c Build.bat WarCardEditor Win64 Development",
      "test_command": "cmd.exe /c RunValidator.bat"
    }
  }
}
```

The run pauses for approval after `impl` (the agentic block) — review the
tool-call history in the UI before clearing the gate. The final `commit`
block also pauses: this is your last chance to inspect the diff before the
squash commit lands locally.

## What each block does

1. **`task_md`** parses the ticket markdown into structured sections
   (`as_is`, `to_be`, `acceptance`) and sets heuristic flags
   (`needs_server`, `needs_client`, `needs_contract_change`, ...).
2. **`impl`** hands the LLM the WarCard repo via six native tools
   (Read/Write/Edit/Glob/Grep/Bash) plus a small bash allowlist.
   System/user prompts interpolate the parsed task.
3. **`build`** runs `build_command`. `allow_nonzero_exit: true` so that
   failures reach `verify_build` instead of crashing the block.
4. **`verify_build`** checks `build.success == true`. On failure it loops
   back to `impl`, injecting `build_stderr` and `build_exit` so the agent
   sees why the compile failed. Max 3 iterations.
5. **`tests`** runs `test_command` the same way.
6. **`verify_tests`** loops back to `impl` on failure with
   `test_failures` injected. Max 3 iterations.
7. **`commit`** runs `git add -A && git commit -m "..."` with the feat_id
   and task title templated in. **No push** — local commit only. Branch
   management, squashing against main, and push are operator steps.

## Loopback feedback

On any iteration where the verify block loops back, `agent_with_tools`
auto-appends the captured diagnostics to the user message:

```
---
Previous attempt (iteration 2) did not pass verification.
Address the issues below before retrying:
- build_stderr: cpp:42: error: no member 'FMatchmakingStatusMessage'
- build_exit: 2
```

The agent session is fresh each iteration (no resume), so the feedback is
the only state carried over — matching the plan's per-iteration session
model.

## Audit trail

Every run persists:

- `llm_call` rows — one per API round-trip, `iteration` field 1..N.
- `tool_call_audit` rows — one per `Tool.execute`, correlatable via
  `(run_id, block_id, iteration)`.
- `block_output` rows — one per block, plus `_loopback_<target>`
  synthetic outputs carrying verify issues across iterations.

Query examples in the H2 console or via `/api/runs/{id}/llm-calls`.

## When the pipeline doesn't fit

- **Layer 3 flows** (MCP / Blueprint generation / anything going through
  the user's Max subscription) — use `claude_code_shell` blocks instead
  of `agent_with_tools`. Same cwd/interpolation semantics, different
  execution path.
- **Non-standard tickets** (ADRs, spikes, docs-only changes) — write a
  custom pipeline YAML. Everything here is a reference, not a mandate.

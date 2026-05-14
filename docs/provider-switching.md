# Provider switching: OpenRouter ↔ Claude Code CLI

> Полный реестр моделей по всем 4 провайдерам (OpenRouter / CLI / Ollama / vLLM), их плюсы/минусы и per-block рекомендации — в [`docs/models.md`](models.md).

Same pipeline can run agentic blocks via two backends:

| Provider | Implementing block | Billing | When to choose |
|---|---|---|---|
| `OPENROUTER` | `agent_with_tools` (and `orchestrator`) | OpenRouter credits | Cheap models, structured tool-use loop with full per-iteration audit, GLM/Qwen/etc. |
| `CLAUDE_CODE_CLI` | `claude_code_shell` | Anthropic Max subscription (local) | Anthropic models without geo-block on the API, MCP-native flows, no per-token cost |

The choice is **per-run** but defaults to a project-level setting.

## How the toggle works

1. Each `Project` has a `defaultProvider` field (`OPENROUTER` | `CLAUDE_CODE_CLI`). UI form: `Settings → Project tab → Default LLM provider`.
2. On `POST /api/runs`, if the request body does not pin `inputs.provider`, the run inherits the project default. The resolved value is stored in `PipelineRun.runInputsJson` under key `provider`.
3. Pipeline blocks gate themselves with `condition:` against `$.input.provider`. Two variants of the same logical step coexist; only one runs.

## Pattern in YAML

```yaml
# Same upstream (plan_impl) — both variants depend on it.
# Mutually exclusive condition values ensure exactly one branch executes.
- id: impl_server_or
  block: agent_with_tools
  condition: "$.input.provider == 'OPENROUTER'"
  depends_on: [plan_impl]
  agent:
    model: "z-ai/glm-4.7-flash"
    temperature: 0.2
    maxTokens: 32768
  config:
    max_iterations: 40
    preload_from: plan_impl
    user_message: "Goal: ${plan_impl.goal}\n\n${plan_impl.approach}\n..."
    working_dir: "/projects/WarCard"
    budget_usd_cap: 5
    allowed_tools: [Read, Write, Edit, Glob, Grep, Bash]
    bash_allowlist:
      - "Bash(git status)"
      - "Bash(./gradlew compileJava*)"

- id: impl_server_cli
  block: claude_code_shell
  condition: "$.input.provider == 'CLAUDE_CODE_CLI'"
  depends_on: [plan_impl]
  config:
    prompt: |
      Goal: ${plan_impl.goal}

      ${plan_impl.approach}

      Files to touch: ${plan_impl.files_to_touch}
      Definition of done: ${plan_impl.definition_of_done}
    working_dir: "/projects/WarCard"
    model: sonnet
    allowed_tools: "Read,Write,Edit,Glob,Grep,Bash(git *),Bash(./gradlew *)"
    permission_mode: acceptEdits
    timeout_sec: 1800
```

Both blocks have the **same `depends_on`** but **different IDs** — the validator and runner permit this. The skipped variant is recorded with `_skipped: true, reason: "condition: ..."`.

## Downstream constraints

Downstream blocks (`build`, `tests`, `verify_acceptance`, `commit`) must **not** reference impl block fields like `${impl_server_or.x}` — only the block that actually ran will have outputs, and template resolution against the skipped one fails loud.

Safe patterns for downstream:
- Read git working tree (`git status`, `git diff`) via `shell_exec`.
- Reference upstream blocks that always run (`task_md`, `analysis`, `plan_impl`).
- Use `verify` checks against shell-block outputs, not impl outputs.

## Loopback

`verify_build`/`verify_acceptance` blocks with `on_fail.action: loopback` need a single `target:`. Today the recommendation is to point loopback at the OpenRouter variant (`impl_server_or`) — re-running through Claude CLI from a verify-fail is a future improvement.

## Iteration history in the UI

| Provider | What rows appear | What's missing |
|---|---|---|
| OpenRouter | One `LlmCall` row per tool-use iteration with full token / cost / `finishReason` / tool-call list | — |
| Claude Code CLI | **One synthetic** `LlmCall` row per block invocation (`iteration=0`, `tokensIn=tokensOut=costUsd=0`) | Per-turn detail (would need `--output-format stream-json` parsing) |

The UI badges these distinctly: 🌐 emerald `OpenRouter` vs 💻 orange `Claude CLI`.

## Switching default provider

```http
PUT /api/projects/{slug}
Content-Type: application/json

{
  "defaultProvider": "CLAUDE_CODE_CLI"
}
```

Or in UI: `Settings → Project tab → Default LLM provider`.

Per-run override (skips the project default):
```http
POST /api/runs
{
  "configPath": "...",
  "inputs": { "provider": "CLAUDE_CODE_CLI", "task_file": "..." }
}
```

## Required integration

Provider `CLAUDE_CODE_CLI` requires the `IntegrationType.CLAUDE_CODE_CLI` integration to be configured (path to the `claude` binary, MCP config). Without it, `claude_code_shell` blocks fail at process start. The platform does **not** auto-fallback to OpenRouter — that's by design (silent fallback hides cost surprises).

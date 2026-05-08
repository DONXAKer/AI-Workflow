---
name: CLI route is tool-less single-shot (anti-pattern)
description: LlmClient.completeWithToolsViaCli ignores tool definitions/executor and returns one shot — agent_verify and other tool-use blocks degrade silently on CLI route
type: project
---

`LlmClient.completeWithToolsViaCli()` (workflow-core/src/main/java/com/workflow/llm/LlmClient.java:581) is **not** a real tool-use loop:

- ignores `request.tools()` / `ToolDefinition` list — only passes hard-coded argv `--allowed-tools Read,Write,Edit,Bash,Glob,Grep --permission-mode acceptEdits`
- ignores the `ToolExecutor` parameter entirely (no DefaultToolExecutor invocation, no ToolCallAudit rows from this path)
- ignores `maxIterations`, `budgetUsdCap`, `progressCallback`
- returns hard-coded `StopReason.END_TURN, iterations=1, tokensIn=0, tokensOut=0, cost=0.0`
- forwards system prompt by **concatenating** `systemPrompt + "\n\n" + userMessage` into a single `-p` argv (no separate `--system-prompt` flag here, unlike `completeViaCli` at line 567)
- `bash_allowlist` from block YAML is **silently dropped** — argv has no `--bash-allowlist` equivalent
- per-block `allowed_tools` config is **silently dropped** — argv override is fixed superset

**Why:** if Project.defaultProvider = CLAUDE_CODE_CLI, the CLI subprocess does its own internal agent loop (claude -p invokes Anthropic's managed agent), so AI-Workflow's outer loop is bypassed. The CLI internal loop *can* read/grep on its own. But:
- AI-Workflow has **no audit** of those internal tool calls (ToolCallAudit table stays empty for these blocks under CLI)
- AI-Workflow has **no token-cost** data (LlmCall row has `tokensIn=0, tokensOut=0, costUsd=0.0`)
- on env-mount issues (~/.claude not mounted, OAuth missing) the CLI's internal loop can return placeholder text without erroring on exit code 0

**How to apply:** when an agentic block (agent_with_tools, agent_verify, orchestrator/review with tool-driven verification) misbehaves under CLI route — don't blame the block prompt first. Check whether it ran through `completeWithToolsViaCli()`:
- LlmCall.provider = CLAUDE_CODE_CLI
- LlmCall row count for that block in that run = 1 (vs N>1 under OpenRouter where every iteration writes a row)
- ToolCallAudit rows for that (run, block) = 0
- LlmCall.tokensIn/tokensOut = 0

If yes, the block ran tool-less. The fix needs to be in LlmClient itself (proper streaming-JSON CLI driver, or use `--output-format stream-json` and parse tool_use events), not in the block prompt.

**Affected blocks** (any with depends on tool-use loop semantics): `agent_with_tools`, `agent_verify`, `orchestrator` (review mode that should grep evidence). They all degrade to single-shot under CLI.

# AI-Workflow Phase 1 — WarCard pipeline native integration

Locked design from grill-me session **2026-04-20**. Source of truth for the work on branch `feat/warcard-pipeline-phase1`. Do not re-design without a new grill session.

## Goal

AI-Workflow как универсальная pipeline-платформа для разных проектов. Первый dogfood — портировать WarCard `/feature` skill в нативный AI-Workflow pipeline через декомпозицию (не перенос skill как чёрного ящика).

**Мотивация:** (b) autonomous runs, (c) multi-user ready, (d) per-step audit/reproducibility, плюс честный dogfood самого AI-Workflow на реальной задаче.

**Why decomposition, not port:** `/feature` как Claude Code skill — чёрный ящик для AI-Workflow. В нативном pipeline каждый шаг — отдельный блок с собственным approval gate, model choice, tool whitelist, per-iteration LlmCall audit, loopback.

## Локированные архитектурные решения

1. **Deploy:** Local JVM (`gradle bootRun`) на Windows-хосте, не Docker. Docker вернётся когда появится multi-tenant.
2. **LLM provider:** OpenRouter only в Phase 1. `LlmClient` имеет abstract layer для будущих провайдеров. Max-подписка Anthropic недоступна через API — только через Layer 3 `claude_code_shell`.
3. **Project model:** *referenced* — запись в БД с абсолютным `working_dir` на том же хосте. `integration_config.project_id nullable` (null = global default).
4. **Project directory layout:** `<project_root>/.ai-workflow/{project.yaml, pipelines/, skills/}`.
5. **Tool set:** 1:1 копия Claude Code built-ins (`Read`, `Edit`, `Write`, `Bash`, `Glob`, `Grep`) — автоматическая совместимость с любыми Claude Code SKILL.md.
6. **Security:**
    - Path validation (level β): tool rejectит пути вне `project.working_dir + working_dir_scope` после canonicalization.
    - Hardcoded destructive deny-list: `git push --force`, `git reset --hard`, `rm -rf`, writes to `.env*`/credentials/`*.pem`/`*.key`. Не override'ится.
    - Bash allowlist синтаксис = Claude Code `settings.json` (`Bash(git *)`, `Bash(gradle *)`, etc).
7. **Tool-use loop:** Anthropic format (через OpenRouter passthrough к `anthropic/*`). Caps: `maxIterations: 40`, per-block `budgetUsdCap`, per-run cumulative cap. Один `LlmCall` row per API iteration для full audit.
8. **Timeouts:** per API call 120s (with retry), per iteration 10 min, per block 30 min (default). `KillSwitch` проверяется между iterations.
9. **Retry:** exponential backoff (1/2/4/8/16 s), max 3 попытки. `overloaded` → `--fallback-model`. Tool errors возвращаются как `is_error:true` в `ToolResult` — LLM решает.
10. **Git flow:** гибрид. Explicit `shell_exec` блоки для `git_branch_create` и `git_finalize`. `agent_with_tools.config.on_success_commit` для WIP-коммитов per step. Squash на финише. Idempotent branch (checkout если существует). Pre-flight dirty check.
11. **State passing:** `${block.field}` — interpolation в YAML строках, `$.block.field` — в expressions (condition/inject). Output блока = `Map<String,Object>`. Fail при missing path, не silent empty.
12. **`task_md_input` block:** парсит русские sections (`## Как сейчас`, `## Как надо`, `## Вне scope`, `## Критерии приёмки`). Auto-extract `feat_id`, `slug`, `title` из filename. Heuristic classification: `needs_contract_change`, `needs_server`, `needs_client`, `needs_bp`, `is_greenfield`.
13. **Verify + loopback:** множественные per-category verify блоки (`verify_contract_drift`, `verify_server_drift`, `verify_client_drift`). Fresh agent session per iteration (не resume). `inject_context` → `_loopback` field в input next iteration. Max 3 итерации (simple count).
14. **Skills как data:** простой file loader. Discovery order: `<project>/.ai-workflow/skills/<name>/SKILL.md` → `<project>/.claude/skills/<name>/SKILL.md` (fallback). Не first-class entity в Phase 1.
15. **Task-tool / subagent emulator:** DEFER. В декомпозированном pipeline sub-task = sub-block.
16. **Java MCP client:** DEFER (5-7 дней + риск). BP step идёт через `claude_code_shell` → `claude -p --mcp-config .mcp.json`. Java/Node MCP client — Phase 3+ через Node.js sidecar.
17. **`claude_code_shell`:** **universal permanent block** в каталоге, не WarCard-specific. Generic inputs: `prompt`, `allowed_tools`, `mcp_config`, `model`, `max_budget_usd`, `timeout_sec`, `working_dir`, `session_mode`, `permission_mode`.
18. **WarCard `/feature` SKILL.md** в `D:\WarCard\.claude\skills\feature\` остаётся как reference doc для прямого Claude Code. В AI-Workflow pipeline **не используется** — заменён YAML-ом.

## Phase 1 новые/изменённые компоненты

1. `LlmClient.completeWithTools(ToolUseRequest, ToolExecutor)` — ядро цикла
2. `ToolRegistry` + 6 нативных `Tool` реализаций с path validation / bash allowlist / deny-list
3. `tool_call` таблица + audit persistence
4. `agent_with_tools` блок
5. `claude_code_shell` блок (universal Layer 3)
6. `task_md_input` блок
7. `shell_exec` блок (с output parsing)
8. `git_finalize` блок
9. `Project` entity + migration `integration_config.project_id nullable`
10. YAML interpolation engine `${...}` + `$.path` expressions
11. Verify-блок extension с `_loopback` injection в `agent_with_tools`

**Старые блоки** (`AnalysisBlock`, `CodeGenerationBlock`, etc.) — `@Deprecated`, существующие YAML продолжают работать, не трогаем.

## Acceptance tests

**Test 1 (WarCard FIX-BOOT-009):** USTRUCTs для `ErrorMessage` + `MatchmakingStatusMessage`. Без BP. End-to-end через `.ai-workflow/pipelines/feature.yaml` → squashed commit в main, validator clean, task.md в `done/`.

**Test 2 (AI-Workflow self-feature):** юзер выбирает на M4–M5 — (α) новый `http_get` block, (β) CLI `workflow list`, (γ) bug fix.

## Milestones (~15 дней total)

| M | Дни | Scope | Deliverable | Статус |
|---|---|---|---|---|
| M1 | 3 | Ядро tool-use + базовая persistence + unit test | `LlmClient.completeWithTools` работает, toy Calculate tool green, `LlmCall` per iteration | в работе |
| M1.1 | — | Прочитать существующую LLM/Skill инфру | — | done |
| M1.2 | — | Дизайн интерфейсов tooluse | `ToolDefinition/Call/Result/ToolExecutor/UseRequest/UseResponse/StopReason` | done (a59c388) |
| M1.3 | — | Реализация `completeWithTools` — Anthropic tool_use format, iteration loop, retry, budget cap | 7798f4b | done |
| M1.4 | — | Persistence — `LlmCall` row per API iteration с `iteration` + `toolCallsMadeJson` (поле уже в entity, осталось писать из loop'а) | — | **next** |
| M1.5 | — | Integration test с toy Calculate tool (реальный OpenRouter или mock) | `@Tag("integration")` JUnit test | — |
| M2 | 3 | 6 нативных tools + path validation + deny-list + bash allowlist + `agent_with_tools` блок + audit `tool_call` таблица + integration test | — | — |
| M3 | 3 | `task_md_input`, `shell_exec`, `claude_code_shell`, verify loopback, Project entity, YAML interpolation | — | — |
| M4 | 3–4 | WarCard `.ai-workflow/` + 4 small skills + **Test 1 pass** | — | — |
| M5 | 2–3 | Self-feature pipeline + **Test 2 pass** | — | — |

Между milestones — approval gate. Юзер ревьювит → ok → новая сессия на следующий milestone.

## Важные замечания про `ToolExecutor`

`ToolExecutor` интерфейс — это **стратегия**, не реализация. В M1 его конкретной реализации **нет**. В unit-test'е M1.5 используется простой in-memory executor (lambda или anonymous impl). Реальный `DefaultToolExecutor` поверх `SkillRegistry` с path validation / bash allowlist / deny-list / аудитом — это **M2**, не M1.4.

`ToolExecutor` docstring упоминает эти вещи как то что *implementation in M2* будет делать — но интерфейс остаётся thin ("execute a call, return a result, don't throw for expected failures").

## Workflow

- Branch: `feat/warcard-pipeline-phase1` в `D:\Проекты\AI-Workflow\`.
- Клод пишет, юзер reviewит и approve'ит.
- Каждый milestone — отдельная Claude Code сессия. Контекст предыдущих сессий — через commits на ветке + этот файл.
- WIP-коммиты внутри milestone, финальный squash-merge в main после user approval.

## Предупреждения

1. **Биллинг раздвоен сознательно:** Layer 2 (`agent_with_tools`) через OpenRouter, Layer 3 (`claude_code_shell`) через Claude Code OAuth (Max подписка юзера).
2. **Multi-user без RBAC в Phase 1.** Project scope в БД есть, auth — DEFER до реальных других пользователей.
3. **Docker AI-Workflow должен быть остановлен** перед стартом M1 (переключение на local JVM).

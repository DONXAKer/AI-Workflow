# E2E flow tests

End-to-end Playwright tests that exercise **every pipeline returned by
`GET /api/pipelines`** through the real backend, real AllTokens LLM provider, and
a fresh mini-target-repo fixture. Screenshots are captured at every meaningful
step for visual audit.

## Prerequisites

1. **docker-compose stack up.** These tests do NOT start a server.
   ```powershell
   docker compose up -d
   ```
   Default expectations:
   - UI: `http://localhost:5120`
   - API: `http://localhost:8020`

2. **AllTokens API key** in `ALLTOKENS_API_KEY`. Without it, the test setup
   throws immediately.

3. **Admin credentials** — defaults to `admin / admin` (works for the
   docker-compose stack on `localhost:8020`). Override via UI rotate if needed.

4. **git** on PATH (used to init the mini-target-repo each spec).

5. **Playwright browsers** installed: `npx playwright install chromium`.

## Run

```powershell
$env:ALLTOKENS_API_KEY = 'sk-...'                    # обязательно

# Опциональные:
$env:WF_UI_BASE  = 'http://localhost:5120'
$env:WF_API_BASE = 'http://localhost:8020'
$env:E2E_KEEP    = '0'                               # удалить temp-repo после прогона
$env:E2E_PIPELINE_EXAMPLE = '1'                      # включить pipeline.example
$env:YOUTRACK_TOKEN = '...'                          # для pipeline.example
$env:GITLAB_TOKEN   = '...'
$env:GITHUB_TOKEN   = '...'

cd workflow-ui
npm install
npx playwright install chromium                      # один раз

npm run test:e2e:flows                               # все pipelines
npm run test:e2e:flows -- --grep ai_workflow         # один pipeline
npm run test:e2e:flows -- tests/e2e/flows/integrity.spec.ts
```

## Что внутри

| File | Purpose |
|---|---|
| `flows/integrity.spec.ts` | Сравнивает FS-enumeration с `GET /api/pipelines` — детектит расхождение с PipelineConfigLoader. Дешёвый, без LLM-вызовов. |
| `flows/pipelines.spec.ts` | Data-driven спек: `for pipeline → describe.serial → for ep → test`. Каждый test запускает реальный run и polls до COMPLETED. |
| `helpers/env.ts` | Загрузка env-vars + проверка `ALLTOKENS_API_KEY`. |
| `helpers/auth.ts` | `loginAsAdmin(page)` через реальную LoginPage. |
| `helpers/api-client.ts` | `apiGet/apiPost/apiPut` с XSRF + X-Project-Slug headers. |
| `helpers/pipelines.ts` | `enumeratePipelines()` — FS-mirror `PipelineConfigLoader.listPipelinesWithSources()`. |
| `helpers/project.ts` | `ensureProject`, `setDefaultProviderAllTokens`, `visitProjectSettings`. |
| `helpers/integrations.ts` | `ensureAllTokensIntegration` (idempotent), `visitIntegrationsTab`. |
| `helpers/target-repo.ts` | `prepareTargetRepo(specSlug)` → tmp folder + git init. |
| `helpers/launch.ts` | `launchPipeline`, `defaultInputsFor`, `visitLaunchTab`. |
| `helpers/approval.ts` | `autoApproveUntilTerminal` — 5-sec polling, posts APPROVE per gate. |
| `helpers/screenshot.ts` | `shot(page, pipeline, ep, n, step)` — deterministic-named PNGs. |
| `helpers/skip-rules.ts` | `pipelineSkipReason`, `entryPointSkipReason` — env-gating. |
| `fixtures/mini-target-repo/` | Минимальный TS-проект + `WF-E2E-001-goodbye.md` task. |

## Скриншоты

Сохраняются в `workflow-ui/test-results/e2e-flows/<pipeline>/<ep>/<NN>-<step>.png`.
Per-EP набор:

| # | Step | Когда |
|---|---|---|
| 01 | `login` | форма логина (один раз / pipeline, в `_setup`) |
| 02 | `integrations` | список интеграций с AllTokens-строкой |
| 03 | `settings-provider` | SettingsTab с defaultProvider=ALLTOKENS |
| 04 | `launch-form` | LaunchTab с выбранным pipeline и EP |
| 05 | `run-started` | RunPage сразу после старта |
| 06 | `approval-dialog` | RunPage на approval-gate (повторяется на каждом gate) |
| 07 | `run-completed` | RunPage с COMPLETED |

## Troubleshooting

- **`ALLTOKENS_API_KEY is not set`** — установи env-переменную перед запуском.
- **401 Unauthorized** — admin password сменён; обнови `ADMIN_PASSWORD` в backend
  или сбрось через admin UI.
- **`POST /api/runs returned no runId`** — конфиг pipeline невалиден (PipelineConfigValidator
  отверг). Проверь `POST /api/pipelines/validate?configPath=...` вручную.
- **AllTokens cold-start ~30-60с** — первый run pipeline может зависать на первом
  LLM-вызове. Polling авто-аппрува таймаут 45 мин по умолчанию, перенастрой через
  `autoApproveUntilTerminal({maxMinutes})` в спеке.
- **`ApprovalTimeoutScheduler`** — если `approval_timeout_seconds` < `maxMinutes`,
  backend сам REJECT'нет gate раньше polling. Это маркер плохо настроенного YAML,
  не теста.
- **`pipeline.example` падает на youtrack_input** — нужны `YOUTRACK_TOKEN` +
  `E2E_YOUTRACK_ISSUE`, плюс реальная YouTrack-интеграция в проекте. По умолчанию
  спек skip'ает этот pipeline.
- **task_md_input ругается на filename** — fixture должна называться
  `<FEAT-ID>-<slug>.md` (regex в `TaskMdInputBlock.java`). Не переименовывай.
- **Project conflict при повторе** — slugs детерминированы (`e2e-{filename}`);
  helper `ensureProject` идемпотентен, повторный прогон переиспользует существующий проект.

## CI?

**Нет** — операторский trigger only:
- 8+ реальных runs × $0.05–1 = $1–8 на прогон
- 2–4 часа времени
- Зависит от внешнего LLM
- Не для каждого PR

Если потребуется CI — добавь `process.env.WF_E2E_FLOWS === '1'` guard в специнфы
и отдельный workflow с manual trigger.

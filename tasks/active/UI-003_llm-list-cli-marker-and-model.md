---
id: UI-003
title: UI — явная модель + CLI-маркер в LLM-list на странице run
created: 2026-05-24
---

## Как сейчас

Страница run в `workflow-ui` показывает блок «LLM calls». Для проектов под **CLI-route** (`Project.defaultProvider = CLI`, например WarCard) все вызовы имеют `tokens_in=0, tokens_out=0, cost_usd=0` — Anthropic Max биллится flat-fee, CLI не возвращает usage. UI агрегирует tokens/cost и если все нули, рендерит пустую секцию или "no LLM activity".

Это **не баг** ([[project_warcard_provider_routing]], [[reference_models_doc]]) — это специфика CLI-route. Но operator не понимает что произошло: «10 LLM вызовов, а в UI пусто».

Source: `LlmCall` JPA entity, `LlmCallRepository`, endpoint `GET /api/runs/{runId}/llm-calls` (см. `RunController.java:1165`). Frontend — `workflow-ui/src/pages/RunDetailPage.tsx` или похожее, секция LLM.

## Как надо

В UI-секции «LLM calls» страницы run:

1. **Список всегда отображается**, даже если все `cost_usd=0`. Каждая строка содержит:
   - `blockId` (например `analysis`, `impl_bp`)
   - `iteration` (если >0)
   - `model` (полное имя: `cli/claude-sonnet-4-6`, `deepseek/deepseek-v4-flash`, `openai/gpt-4o-mini`, ...)
   - `tokens` — `123↑/456↓` или `—` если оба нуля
   - `cost` — `$0.0123` или явный маркер `flat-fee (CLI)` когда модель начинается с `cli/`

2. **Header секции** показывает агрегат:
   - `total: N calls, $X.XX` для paid routes
   - `total: N calls, flat-fee (CLI Anthropic Max)` для CLI route — если ВСЕ вызовы run-а под CLI
   - Mixed (часть CLI, часть paid) — `total: N calls, $X.XX + M CLI calls`

3. **Подсказка-tooltip** на маркере `flat-fee (CLI)`: "Anthropic Max subscription billing — per-token usage не доступен через CLI"

## Вне scope

- НЕ менять `LlmCall` schema или persisted данные
- НЕ переключать модели/providers
- НЕ добавлять реальный CLI billing tracking (это требует Anthropic dashboard scraping)
- Не трогать backend endpoint — изменения только frontend

## Risk-areas

- [ ] performance (не critical, ~10-30 rows per run)
- [ ] security (не critical, internal admin UI)
- [ ] backward compat (новое UI, существующие run-ы тоже корректно отобразятся)

## Критерии приёмки

- [ ] Открыть run WarCard (CLI route) — секция «LLM calls» показывает все вызовы, у каждого видна модель `cli/claude-*`
- [ ] Header секции для этого run показывает `total: N calls, flat-fee (CLI Anthropic Max)`
- [ ] Открыть run проекта на OpenRouter (paid) — header показывает `total: N calls, $X.XX`, у каждого вызова cost > 0
- [ ] Открыть legacy run где cost есть но tokens=0 (если такие есть) — корректно рендерит cost, не падает
- [ ] Hover на `flat-fee (CLI)` маркер → tooltip с объяснением
- [ ] Существующие endpoint'ы не менялись (бэкенд не трогается)
- [ ] Ветка squash'нута, task.md в `done/`

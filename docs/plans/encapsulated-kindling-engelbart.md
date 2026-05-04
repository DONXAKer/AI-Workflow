# Шаблон задачи для skill_marketplace

## Context

Существующий `docs/task-template.md` минималистичен — 4 секции без подсказок.
При создании новых задач пользователь не видит:
- соглашение по именованию файла (`SM-NNN_slug.md`)
- какие поля парсит пайплайн (TaskMdInputBlock)
- как запустить задачу через AI-Workflow после создания

Цель: обновить шаблон так, чтобы создание новой задачи и её запуск в пайплайне
занимали минимум времени и не требовали обращения к документации.

---

## Что делаем

### 1. Обновить `docs/task-template.md`

Добавить:
- **Шапку-комментарий** с соглашением по именованию (`SM-NNN_slug.md`)
- **Улучшенные placeholder-тексты** в каждой секции с конкретными подсказками
- **Секцию `## Технический контекст`** (опциональная) — пути файлов/компонентов,
  куда смотреть; попадает в `body` и помогает агентам быстрее ориентироваться в коде
- **Блок `## Запуск`** в конце (в HTML-комментарии) — готовая curl-команда

### 2. Создать `/Users/home/Code/skill_marketplace/tasks/TEMPLATE.md`

Копия шаблона прямо в папке задач — не нужно идти в другой репозиторий.

---

## Итоговое содержимое обоих файлов

```markdown
<!--
  Именование файла: SM-NNN_короткий-slug.md
  Положить в: tasks/active/
-->

# Краткий заголовок задачи (станет заголовком коммита)

## Как сейчас
Что происходит сейчас и почему это плохо. Факты, не оценки.
Укажи конкретные URL, компоненты, поля БД, если применимо.

## Как надо
Целевое поведение. Можно включать:
- конкретные файлы для изменения
- API-контракт (endpoint, тело запроса/ответа)
- SQL-схему (новые поля, индексы)
- UI-состояния (до / после)

## Вне scope
- Что явно НЕ делаем в этой задаче
- Смежные улучшения — оставляем на потом

## Критерии приёмки
- [ ] Верифицируемое условие 1
- [ ] Верифицируемое условие 2

## Технический контекст
<!-- Опционально. Помогает агенту быстрее найти нужный код. -->
- Backend: `api/src/main/java/...`
- Frontend: `web/app/.../page.tsx`, `web/components/...`
- Миграции: `api/src/main/resources/db/migration/`
- Связанные сущности: `SkillEntity`, `ReviewRepository` и т.д.

---
<!--
## Запуск через AI-Workflow

# 1. Логин (один раз, сохраняет куки в /tmp/wf.cookie)
curl -s -c /tmp/wf.cookie -X POST http://localhost:8020/api/auth/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}'
COOKIE=$(grep JSESSIONID /tmp/wf.cookie | awk '{print $7}')
XSRF=$(grep XSRF /tmp/wf.cookie | awk '{print $7}')

# 2. Старт (подставить SM-NNN_slug в task_file)
curl -s \
  -H "Cookie: JSESSIONID=$COOKIE; XSRF-TOKEN=$XSRF" \
  -H "X-XSRF-TOKEN: $XSRF" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8020/api/runs \
  -d '{
    "configPath": "./config/skill_marketplace.yaml",
    "entryPointId": "from_task_file",
    "autoApproveAll": false,
    "inputs": { "task_file": "/projects/skill_marketplace/tasks/active/SM-NNN_slug.md" }
  }'

# 3. Одобрить блок plan (UI может падать на сложном JSON — одобрять через API)
curl -s -X POST \
  -H "Cookie: JSESSIONID=$COOKIE; XSRF-TOKEN=$XSRF" \
  -H "X-XSRF-TOKEN: $XSRF" \
  -H "Content-Type: application/json" \
  "http://localhost:8020/api/runs/{RUN_ID}/approval" \
  -d '{"blockId":"plan","decision":"APPROVE"}'
-->
```

---

## Файлы для изменения

| Файл | Действие |
|------|----------|
| `workflow-core/docs/task-template.md` | Перезаписать улучшенным шаблоном |
| `/Users/home/Code/skill_marketplace/tasks/TEMPLATE.md` | Создать новый файл |

---

## Верификация

1. Прочитать оба файла — убедиться, что структура читаема
2. Убедиться, что HTML-комментарии `<!-- -->` не ломают парсинг:
   TaskMdInputBlock ищет секции по `## Заголовок` — комментарии попадают в `body`,
   но не мешают извлечению `as_is`, `to_be`, `out_of_scope`, `acceptance`

---

<!-- Предыдущий план (Claude в Docker) — реализован, заменён новой задачей -->

1. **`workflow-core/Dockerfile`** — `npm install -g @anthropic-ai/claude-code`; `claude --version` = 2.1.126 в контейнере ✅

2. **`workflow-core/src/main/java/com/workflow/llm/LlmClient.java`**:
   - Метод `shouldUseCli(model)`: пресеты и `anthropic/*` → CLI; `google/*`, `deepseek/*` и т.д. → OpenRouter
   - Прогресс: `[google/gemini-2.5-flash] Итерация 3/20`

3. **`workflow-core/src/main/java/com/workflow/api/RunController.java`** — `GET /api/runs/{id}/llm-calls` (blockId, iteration, model, tokensIn, tokensOut, costUsd)

4. **`workflow-ui/src/types.ts`** — `LlmCallEntry` интерфейс

5. **`workflow-ui/src/services/api.ts`** — `getRunLlmCalls(runId)`

6. **`workflow-ui/src/components/BlockProgressTable.tsx`** — badge модели + токены в каждой IterationRow

7. **`workflow-ui/src/pages/RunPage.tsx`** — загрузка и поллинг llm-calls каждые 5s

### Оставшийся шаг: создать CLAUDE_CODE_CLI интеграцию в БД

```bash
curl -s -c /tmp/wf.cookie -X POST http://localhost:8020/api/auth/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}'
COOKIE=$(grep JSESSIONID /tmp/wf.cookie | awk '{print $7}')
XSRF=$(grep XSRF /tmp/wf.cookie | awk '{print $7}')
curl -X POST http://localhost:8020/api/integrations \
  -H "Cookie: JSESSIONID=$COOKIE; XSRF-TOKEN=$XSRF" \
  -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" \
  -d '{"name":"claude-cli","type":"CLAUDE_CODE_CLI","displayName":"Claude Code CLI (Max)","baseUrl":"claude","token":"","isDefault":true}'
```

После этого — пресеты `smart`/`reasoning` в yaml пойдут через CLI (claude-sonnet/opus); `google/gemini-*` по-прежнему через OpenRouter.

---

# Анализ моделей по типам задач пайплайна (архивный план)

## Context

Пайплайн skill_marketplace состоит из 7 блоков с разными характеристиками LLM-нагрузки.
Сейчас все блоки используют два тира: `smart` и `reasoning` — оба маппятся на Claude CLI.
Цель — определить оптимальную модель для каждого блока с учётом качества и стоимости,
и сформировать рекомендованный конфиг для двух сценариев: CLI (подписка) и OpenRouter (pay-per-token).

Реальные данные из запуска SM-004 (13м 5с):
- `plan`: 9 итераций, ~135K input / ~9K output, оценочно $0.91
- `codegen`: 40 итераций (MAX_ITER), **586K input / 12K output**, $0.094 через CLI

> Цены верифицированы через OpenRouter API (`GET /api/v1/models`) — 2026-05-02.

---

## Цены моделей (верифицированы через API, май 2026)

| Модель | OpenRouter ID | Input $/1M | Output $/1M | Context | Плюсы | Минусы |
|--------|--------------|-----------|-------------|---------|-------|--------|
| **Gemini 2.5 Flash Lite** | `google/gemini-2.5-flash-lite` | $0.10 | $0.40 | **1M** | Дешевейший с 1M ctx, tools ✅ | Слабее Flash |
| **Gemini 2.0 Flash** | `google/gemini-2.0-flash-001` | $0.10 | $0.40 | **1M** | Дёшево, 1M ctx, tools ✅ | Старше Flash |
| **DeepSeek V4 Flash** | `deepseek/deepseek-v4-flash` | $0.14 | $0.28 | **1M** | Новый, дёшево, 1M ctx, tools ✅ | Мало бенчмарков |
| **GPT-4o Mini** | `openai/gpt-4o-mini` | $0.15 | $0.60 | **128K** | Проверен, дёшево | Ctx 128K — мал для codegen |
| **DeepSeek Chat V3.1** | `deepseek/deepseek-chat-v3.1` | $0.15 | $0.75 | **32K** | Дёшево | Ctx 32K — слишком мал |
| **DeepSeek Chat V3** | `deepseek/deepseek-chat-v3-0324` | $0.20 | $0.77 | 163K | Хороший codegen | Слабее reasoning |
| **Grok 4.1 Fast** | `x-ai/grok-4.1-fast` | $0.20 | $0.50 | **2M** | Новый, 2M ctx, tools ✅ | Мало бенчмарков |
| **Gemini 2.5 Flash** | `google/gemini-2.5-flash` | $0.30 | $2.50 | **1M** | Баланс цена/качество, tools ✅ | Output дороже |
| **DeepSeek R1 0528** | `deepseek/deepseek-r1-0528` | $0.50 | $2.15 | **163K** | Сильный reasoning, tools ✅ | 163K ctx (мало для plan) |
| **DeepSeek R1** | `deepseek/deepseek-r1` | $0.70 | $2.50 | 64K | Reasoning | Ctx 64K — **не подходит для plan** |
| **Claude Haiku 4.5** | `anthropic/claude-haiku-4.5` | $1.00 | $5.00 | 200K | Быстрый | Ограниченный ctx |
| **DeepSeek V4 Pro** | `deepseek/deepseek-v4-pro` | $0.43 | $0.87 | **1M** | Новый, дёшево, 1M ctx, tools ✅ | Мало бенчмарков |
| **Gemini 2.5 Pro** | `google/gemini-2.5-pro` | $1.25 | $10.00 | **1M** | Лучший reasoning+tools, 1M ctx | Дорогой output |
| **GPT-4o** | `openai/gpt-4o` | $2.50 | $10.00 | 128K | Надёжный | Ctx 128K, не лучше Gemini Pro |
| **Claude Sonnet 4.6** | `anthropic/claude-sonnet-4.6` | $3.00 | $15.00 | 1M | Отличный codegen | Дорогой (только через CLI) |
| **Claude Opus 4.7** | `anthropic/claude-opus-4.7` | $5.00 | $25.00 | 1M | Лучший orchestrator | Очень дорогой (только через CLI) |
| **Claude Max (CLI)** | — | **$0** | **$0** | 1M | Фикс $100–200/мес, все Claude модели | Лимит usage, нет параллелизма |

> ⚠️ Anthropic-модели через OpenRouter дороже в 2–5× — использовать только через CLI.
> ⚠️ GPT-4o Mini: ctx=128K — слишком мал для codegen (586K+ tokens).

---

## Характеристики блоков

| Блок | Тип задачи | LLM-вызовов | Tokens (реальные/оценка) | Reasoning | Min ctx |
|------|-----------|------------|--------------------------|-----------|---------|
| `task_md` | Парсинг (без LLM) | 0 | — | — | — |
| `analysis` | Архитектурный анализ | 1 | 10K in / 3K out | Средний | ~15K |
| `clarification` | Уточнение требований | 2 | 16K in / 4K out | Слабый | ~25K |
| `plan` | Планирование + tool use | 8–12 | **135K in / 9K out** | **Сильный** | **≥200K** |
| `codegen` | Кодогенерация + tool use | 10–20 | **586K in / 12K out** | Умеренный | **≥700K** |
| `build_test` | Shell (без LLM) | 0 | — | — | — |
| `review` | Code review + tool use | 6–10 | 56K in / 12K out | **Сильный** | ≥80K |

---

## Рекомендации по моделям на блок

### `analysis` — архитектурный анализ, 1 вызов
**Требования:** знание кода, JSON output, context из knowledge base (~10K), reasoning средний.

| Вариант | Модель | Стоимость/запуск | Рекомендация |
|---------|--------|-----------------|--------------|
| CLI | claude-sonnet-4-6 | $0 (подписка) | ✅ Оптимально |
| OpenRouter качество | `google/gemini-2.5-pro` | $0.04 | ✅ Надёжный |
| OpenRouter экономия | `deepseek/deepseek-v4-flash` | $0.002 | ⚠️ Риск качества анализа |

**→ Оставить `smart`** (CLI: sonnet, fallback: `google/gemini-2.5-pro`)

---

### `clarification` — уточнение, 2 простых вызова, пропускается в 80% случаев
**Требования:** сформулировать вопросы + синтезировать ответы. Простая задача, малый контекст.

| Вариант | Модель | Стоимость/запуск | Рекомендация |
|---------|--------|-----------------|--------------|
| CLI | claude-haiku-4-5 | $0 (подписка) | ✅ Быстро и бесплатно |
| OpenRouter | `google/gemini-2.5-flash-lite` | $0.001 | ✅ Дешевле gpt-4o-mini, 1M ctx |
| OpenRouter alt | `openai/gpt-4o-mini` | $0.002 | ✅ Проверен, 128K ctx |

**→ Понизить до `flash`** (CLI: haiku, fallback: `google/gemini-2.5-flash-lite`).
Gemini 2.5 Flash Lite дешевле gpt-4o-mini ($0.10 vs $0.15/M) и имеет 1M ctx (vs 128K).

---

### `plan` — планирование, 8–12 итераций с tool use
**Требования:** сильный reasoning + tool calling, читает реальный код (много Read/Glob), **135K tokens** — нужен 200K+ ctx.

| Вариант | Модель | Стоимость/запуск | Рекомендация |
|---------|--------|-----------------|--------------|
| CLI | claude-opus-4-7 | $0 (подписка) | ✅ Лучший |
| OpenRouter | `google/gemini-2.5-pro` | $0.26 | ✅ 1M ctx + tools, Deep Think |
| OpenRouter экономия | `deepseek/deepseek-r1-0528` | $0.09 | ⚠️ 163K ctx — мало (135K in) |
| OpenRouter дёшево | `deepseek/deepseek-r1` | — | ❌ 64K ctx — **не подходит** |

**→ Оставить `reasoning`** (CLI: opus-4-7, fallback: `google/gemini-2.5-pro`)
DeepSeek R1 0528 имеет 163K ctx, что теоретически вмещает 135K input, но почти нет запаса.

---

### `codegen` — кодогенерация, 10–20 итераций, **586K+ input tokens**
**Требования:** качественный code gen, tool calling, **1M+ ctx обязателен**, большой output (12K+).

> GPT-4o-mini (128K ctx) и DeepSeek R1 (64K), DeepSeek Chat V3.1 (32K) — **не подходят**.

| Вариант | Модель | Стоимость/запуск | Рекомендация |
|---------|--------|-----------------|--------------|
| CLI | claude-sonnet-4-6 | $0 (подписка) | ✅ Лучший codegen, бесплатно |
| OpenRouter баланс | `google/gemini-2.5-pro` | $0.85 | ✅ Топ codegen + 1M ctx |
| OpenRouter экономия | `google/gemini-2.5-flash` | $0.21 | ✅ 4× дешевле, 1M ctx, tools |
| OpenRouter ультра | `deepseek/deepseek-v4-flash` | $0.09 | ⚠️ 1M ctx, новая модель |

**→ Оставить `smart`** (CLI: sonnet, fallback: `google/gemini-2.5-pro`)

---

### `review` — code review, 6–10 итераций
**Требования:** сильный reasoning, чёткий JSON с verdict, понимание DoD.

| Вариант | Модель | Стоимость/запуск | Рекомендация |
|---------|--------|-----------------|--------------|
| CLI | claude-opus-4-7 | $0 (подписка) | ✅ Лучший reviewer |
| OpenRouter | `google/gemini-2.5-pro` | $0.19 | ✅ Deep Think + 1M ctx |
| OpenRouter экономия | `deepseek/deepseek-r1-0528` | $0.05 | ✅ Сильный reasoning, 163K ctx |

**→ Оставить `reasoning`** (CLI: opus-4-7, fallback: `google/gemini-2.5-pro`)

---

## Итоговая стоимость одного запуска (верифицированные цены)

Расчёт на реальных токенах SM-004: codegen 586K/12K, plan 135K/9K, review 56K/12K.

| Сценарий | analysis | clar. | plan | codegen | review | **ИТОГО** |
|----------|---------|-------|------|---------|--------|-----------|
| **Claude CLI (подписка $100/мес)** | $0 | $0 | $0 | $0 | $0 | **$0** |
| **OR: всё Gemini 2.5 Pro** | $0.04 | $0.001 | $0.26 | $0.85 | $0.19 | **$1.34** |
| **OR: Gemini Pro (reasoning) + Flash (smart)** | $0.01 | $0.001 | $0.26 | $0.21 | $0.19 | **$0.67** |
| **OR: Gemini Pro (reasoning) + V4 Pro (smart)** | $0.007 | $0.001 | $0.26 | $0.26 | $0.19 | **$0.72** |
| **OR: DeepSeek (R1-0528 reasoning + V4-Flash smart)** | $0.002 | $0.001 | $0.09 | $0.09 | $0.05 | **$0.23** |
| **OR: Anthropic через OpenRouter (без CLI)** | $0.05 | $0.03 | $1.64 | $9.66 | $3.48 | **$14.86** |

> Okупаемость CLI ($100/мес): при 75+ запусков/мес через Gemini Pro+Flash ($1.34/run) — выгоднее подписка.

---

## Рекомендуемые изменения конфига

### Тиры → модели (итог)

| Тир | CLI (текущий) | OpenRouter (рекомендованный fallback) | Статус |
|-----|--------------|--------------------------------------|--------|
| `smart` | claude-sonnet-4-6 | `google/gemini-2.5-pro` | ✅ Уже в коде |
| `flash` | claude-haiku-4-5 | `google/gemini-2.5-flash-lite` | ⚠️ Сменить с `openai/gpt-4o-mini` |
| `reasoning` | claude-opus-4-7 | `google/gemini-2.5-pro` | ✅ Уже в коде |

**Почему `gemini-2.5-flash-lite` вместо `gpt-4o-mini`:** дешевле ($0.10 vs $0.15/M input),
контекст 1M vs 128K. `gpt-4o-mini` имеет ctx=128K — слишком мал для больших блоков.

### Блоки → тиры

| Блок | Сейчас | Рекомендация | Изменение |
|------|--------|-------------|-----------|
| `analysis` | smart | smart | — |
| `clarification` | smart | **flash** | Понизить — задача простая |
| `plan` | reasoning | reasoning | — |
| `codegen` | smart | smart | — |
| `review` | reasoning | reasoning | — |

---

## Файлы для изменения

1. **`workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java`**
   - `flash` DEFAULTS: `openai/gpt-4o-mini` → `google/gemini-2.5-flash-lite`

2. **`workflow-core/config/skill_marketplace.yaml`**
   - блок `clarification`: `model: smart` → `model: flash` (уже в файле)

3. **`skill_marketplace/.ai-workflow/pipelines/pipeline.yaml`**
   - блок `clarification`: `model: smart` → `model: flash` (уже в файле)

> Пункты 2 и 3 уже были выполнены в предыдущей сессии — проверить перед применением.

---

## Верификация

1. Перезапустить бэкенд: `cd workflow-core && gradle bootRun`
2. Проверить CLI: в логах должно быть `Calling Claude CLI: model=claude-sonnet-4-6` (или opus/haiku)
3. Проверить тир flash: запустить run где clarification активен, в логах — `model=claude-haiku-4-5`
4. Если OpenRouter: проверить что `flash` → `gemini-2.5-flash-lite`, не `gpt-4o-mini`

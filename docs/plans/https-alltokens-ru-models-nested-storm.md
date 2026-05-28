# Plan: Model Tier Upgrade (alltokens.ru, May 2026)

## Context

Текущие тиры в `Models.java` были зафиксированы под DeepSeek V3.2 и GLM-4.7, которые на тот момент были лучшим соотношением цена/качество на OpenRouter. Каталог alltokens.ru (May 2026) показывает поколение новых моделей с заметно лучшими рейтингами и сопоставимыми ценами. Цель — обновить маппинг тиров OpenRouter-роута (`DEFAULTS` в `Models.java`), не трогая CLI/Ollama/vLLM.

---

## Текущее состояние (OpenRouter DEFAULTS)

| Tier | Модель | Цена in (₽/M) | Роль |
|------|--------|---------------|------|
| `smart` | `deepseek/deepseek-v3.2` | ~25 | анализ, ревью, plan |
| `flash` | `z-ai/glm-4.7-flash` | неизвестно | codegen, agent_with_tools |
| `fast` | `google/gemini-2.5-flash-lite` | ~9 | quick intake |
| `reasoning` | `google/gemini-2.5-pro` | ~115 | hard cases |
| `cheap` | `openai/gpt-4o-mini` | ~14 | bulk, non-critical |

---

## Данные alltokens.ru (372 модели, выборка с рейтингами)

| Рейтинг | Модель | Input (₽/M) | Output (₽/M) | Ctx |
|---------|--------|-------------|--------------|-----|
| **#4** | DeepSeek V4 Flash | 14 | 28 | 1.05M |
| **#5** | Poolside Laguna M.1 | Free | Free | 131K |
| **#6** | Z.ai GLM 5.1 | 118 | 369 | 203K |
| **#11** | MoonshotAI Kimi K2.6 | 88 | 407 | 262K |
| **#14** | Claude Opus 4.7 | 575 | 2,875 | 1.00M |
| **#18** | Xiaomi MiMo-V2.5-Pro | 120 | 359 | 1.05M |
| — | DeepSeek V4 Pro | 55 | 109 | 1.05M |
| — | Qwen3.6 Flash | 24 | 140 | 1.00M |
| — | Google Gemini 3.1 Flash Lite | 32 | 187 | 1.05M |
| — | Google Gemini 3.5 Flash (NEW) | 173 | 1,035 | 1.05M |
| — | Ling-2.6-flash (inclusionAI) | **2** | 4 | 262K |
| — | Gemma 4 26B A4B | 8 | 38 | 262K |

> Примечание: Claude Opus 4.7 (#14) не подходит для OpenRouter-роута — Anthropic-модели зарезервированы для CLI-роута (правило `LlmClient.resolveModel`).

---

## Рекомендованные изменения

### Вариант A — Консервативный (минимальный риск)

Только самое очевидное: заменяем `cheap` на DeepSeek V4 Flash — та же цена, рейтинг #4.

| Tier | Было | Станет | Δ цены |
|------|------|--------|--------|
| `cheap` | `openai/gpt-4o-mini` (~14 ₽/M) | `deepseek/deepseek-v4-flash` (14 ₽/M) | нейтральный |

### Вариант B — Умеренный (рекомендуется)

| Tier | Было | Станет | Δ цены | Обоснование |
|------|------|--------|--------|-------------|
| `smart` | `deepseek/deepseek-v3.2` | `deepseek/deepseek-v4-pro` | +2x (→55 ₽/M) | Прямой преемник V3, контекст 1.05M |
| `flash` | `z-ai/glm-4.7-flash` | `deepseek/deepseek-v4-flash` | вероятно дешевле | #4 глобально, 1.05M ctx |
| `cheap` | `openai/gpt-4o-mini` | `deepseek/deepseek-v4-flash` | нейтрально | #4 по той же цене |
| `fast` | `google/gemini-2.5-flash-lite` | `google/gemini-3.1-flash-lite` | +3x (→32 ₽/M) | Поколение выше, 1.05M ctx |
| `reasoning` | `google/gemini-2.5-pro` | *(оставить)* или `google/gemini-3.5-flash` | +50% | Gemini 3.5 Flash — NEW, проверить |

**Ключевой инсайт:** DeepSeek V4 Flash (#4 в рейтинге, 14 ₽/M) — лучший value в каталоге. Заменяет и `flash`, и `cheap` без роста затрат, при этом качество резко выше GLM-4.7 и gpt-4o-mini.

### Вариант C — Агрессивный

Дополнительно к Б:

| Tier | Станет | Обоснование |
|------|--------|-------------|
| `reasoning` | `google/gemini-3.5-flash` (173 ₽/M) | Новая модель, 1.05M ctx, NEW |
| `smart` | `moonshot-ai/kimi-k2.6` (88 ₽/M) | Рейтинг #11, сильнее для анализа |

---

## ⚠️ Критические оговорки

1. **OpenRouter ID**: Имена на alltokens.ru ≠ ID на OpenRouter. Перед коммитом нужно проверить реальные slug'и через `GET https://openrouter.ai/api/v1/models`. Предполагаемые ID:
   - `deepseek/deepseek-v4-flash` или `deepseek/deepseek-chat-v4-flash`
   - `deepseek/deepseek-v4-pro`
   - `z-ai/glm-5.1`
   - `google/gemini-3.1-flash-lite`
   - `moonshot-ai/kimi-k2.6`

2. **Gemini 3.x**: У Google нестандартная нумерация версий — нужно убедиться, что модель есть на OpenRouter, а не только на alltokens.ru/Google AI Studio.

3. **Рейтинг alltokens.ru** — внутренний рейтинг агрегатора, не обязательно совпадает с LMSYS Chatbot Arena или общепринятыми бенчмарками. Стоит сверить хотя бы топовые модели.

---

## Файлы для изменения

- **`workflow-core/src/main/java/com/workflow/llm/Models.java`** — константы `OR_SMART`, `OR_FLASH`, `OR_FAST`, `OR_REASONING`, `OR_CHEAP` и расширенные пресеты (`DEEPSEEK_V3`, `GLM` → обновить до V4/GLM5.1)
- **`workflow-core/src/main/resources/application.yaml`** — секция `workflow.model-presets` (обновить комментарии/примеры)

---

## Верификация

1. Перед изменением кода — проверить наличие ID на OpenRouter:
   ```bash
   curl -s https://openrouter.ai/api/v1/models | jq '[.data[].id | select(contains("deepseek-v4"))]'
   ```
2. `gradle test` — unit-тесты не обращаются к LLM, должны проходить
3. Запустить backend + тестовый pipeline run, проверить `LlmCall` в H2 (`http://localhost:8020/h2-console`) — убедиться что в `model` пишется новый slug, а не fallback
4. Проверить лог `ModelPresetResolver` на warn-сообщения о неизвестном тире (означает, что ID не распознан и применился fallback)

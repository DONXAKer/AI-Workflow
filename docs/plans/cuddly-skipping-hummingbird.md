# Hardening AI-Workflow: валидация конфигов, робастность на моделях, рантайм

## Context

Аудит пайплайна и блоков (3 параллельных Explore + Plan, всё сверено с исходниками) выявил три кластера проблем, из-за которых оператор и слабые модели роняют пайплайн в рантайме там, где этого можно избежать на этапе конфигурации/вызова:

1. **Дыры валидатора.** `PipelineConfigValidator` ловит структуру/граф/data-flow/фазы, но НЕ проверяет: неизвестный `agent.tier`/`model` (молча проходит как raw id → API-ошибка, `ModelPresetResolver.resolve():98-111`), `temperature`/`maxTokens`/`timeout`/`retry` вне диапазонов, синтаксис JSONPath в `condition`/`gates`, имена `verify.checks[].rule`, `skills[]`, `entry_points` inject/requires_input. Всё это падает в рантайме.
2. **Хрупкость на слабых моделях.** `LlmClient.complete()` вообще не имеет параметра `responseFormat` — JSON-режим форсится только в tool-use пути. `AnalysisBlock`/`CodeGenerationBlock`/`VerifyBlock` вызывают `complete()` без JSON-режима; codegen при невалидном JSON **молча теряет задачу** (`continue`), verify пробивает Exception наверх. Провайдеры (Ollama/vLLM) умеют `response_format`, но single-shot путь его не пробрасывает. Эталонный устойчивый парсер уже есть — `AgentVerifyBlock.parseFinalJson:456-491` (depth-matching + anchor + fallback).
3. **Рантайм-неэффективность.** loopback `max_iterations` и escalation cloud `maxIterations` — две независимые системы отсчёта (до ~8 прогонов цепочки). Нет таймаута на сам вызов блока (только на approval). `loop_history_json` растёт безгранично. `NoProgressDetector` отключается при `null`. Метрики слепы к loopback/no-progress/escalation/token-waste.

**Цель:** ловить ошибки конфига на save/pre-run, делать парсинг устойчивым на всех маршрутах моделей (cloud + локальные равноценно), убрать мультипликативный прожиг токенов и добавить наблюдаемость. Все правки аддитивны и backwards-compatible.

Решения пользователя: формат — **план правок кода**; приоритет — **все три кластера**; модели — **все маршруты равноценно**.

---

## Кластер 1 — Дыры валидатора (fail-fast на этапе конфига)

Все правки аддитивны внутри `PipelineConfigValidator` (`workflow-core/.../config/PipelineConfigValidator.java`). Валидатор накапливает ошибки и не бросает — новые проверки встраиваются чисто. **Правило backwards-compat: то, что отвергло бы текущие рабочие конфиги, делать `WARN`, не `ERROR`** (прецедент `WEAK_LLM_CHECK_PROMPT`/`REF_UNKNOWN_FIELD`). Математически сломанные значения, которые и так падают в рантайме → `ERROR`.

Новые коды (константы в блоке `public static final String`):

| Код | Severity | Триггер |
|---|---|---|
| `UNKNOWN_TIER` | WARN | `agent.tier` задан, без `/`, не входит в объединение ключей всех preset-карт (см. ниже) |
| `TEMPERATURE_OUT_OF_RANGE` | ERROR | `agent.temperature` вне [0.0, 2.0] |
| `MAX_TOKENS_INVALID` | ERROR | `agent.maxTokens <= 0` (верхнюю границу НЕ проверять — лимит per-model/route) |
| `RETRY_INVALID` | ERROR | `max_attempts <= 0` / `backoff_ms < 0` / `backoff_ms > max_backoff_ms` |
| `TIMEOUT_INVALID` | ERROR | `timeout_seconds <= 0` при наличии |
| `GATE_EXPR_SYNTAX` | WARN | несбалансированные скобки или неизвестный оператор в `condition`/`required_gates[].expr` |
| `VERIFY_CHECK_UNKNOWN_RULE` | WARN | `verify.checks[].rule` не из набора известных правил |
| `VERIFY_CHECK_UNKNOWN_FIELD` | WARN | `verify.checks[].field` нет в declared outputs subject-блока |
| `LLM_CHECK_SCORE_RANGE` | ERROR | `verify.llm_check.minScore` вне шкалы (0-10) |
| `SKILL_UNKNOWN` | WARN | элемент `skills[]` не зарегистрирован |
| `ENTRY_INJECT_SOURCE_UNKNOWN` | WARN | `entry_points[].inject[].source` неразрешим / опечатка в `requires_input` |

Точки встраивания:
- **tier/temperature/maxTokens** — новый `validateAgentConfig(...)`, вызов из Level-1 цикла после проверки типа блока. Внедрить `ModelPresetResolver` в конструктор валидатора (сейчас только `BlockRegistry`); добавить на резолвере `Set<String> knownTierKeys()` = объединение `DEFAULTS` + `CLI_DEFAULTS` + `VLLM_DEFAULTS` + `OLLAMA_DEFAULTS` keySet. **Под требование «все маршруты равноценно»: tier валиден, если резолвится на ЛЮБОМ маршруте** (активный провайдер пинится в рантайме/проекте). Skip когда `model` содержит `/`, `tier == null`, или совпадает с ключом override `workflow.model-presets.*`.
- **retry** — `validateRetry(...)` в Level-2 (рядом с escalation, дефолты 3/1000/30000 валидны → срабатывает только на YAML-override).
- **timeout** — только числовая проверка `<= 0`. `on_timeout.action` **уже** валидируется Jackson (`TimeoutConfig.Action.fromValue` бросает на unknown) — НЕ дублировать, отметить в javadoc.
- **condition/gate syntax** — расширить существующие Level-3 ref-проходы: после `extractRefs` лёгкая лексическая проверка `checkExprSyntax` (баланс `()[]{}` + whitelist операторов), WARN-only (рантайм-эвалюатор остаётся истиной).
- **verify.checks rule/field** — `validateVerifyChecks(...)`: набор `KNOWN_VERIFY_RULES` собрать из dispatch-цепочки `VerifyBlock`; field-проверка переиспользует существующий `checkOutputField` против declared outputs subject-блока.
- **llm_check.minScore** — свернуть в существующий `validateLlmCheckPrompt` (переименовать в `validateLlmCheck`).
- **skills / entry_points inject** — `validateSkills` (набор из реестра скилов) + расширить entry-point цикл.

**Тесты** (`PipelineConfigValidatorTest`): по одному фокусному на код + регрессия «известный рабочий production-YAML даёт 0 ERROR».

**Риск: НИЗКИЙ** — чистая аддитивность, рантайм не трогается.

---

## Кластер 2 — Робастность на слабых моделях (JSON-режим + общий парсер)

### 2A. Общий util извлечения JSON (делать ПЕРВЫМ)
Вынести эталонный парсер `AgentVerifyBlock.parseFinalJson` (depth-matching/anchor/fallback) в новый `com.workflow.llm.JsonExtractor`:
```java
static <T> T extract(String text, String anchorKey, TypeReference<T> type, ObjectMapper mapper)
static Map<String,Object> extractObject(String text, String anchorKey, ObjectMapper mapper)
```
Лесенка стратегий (обобщённо из `AgentVerifyBlock` + strip code-fence из `AnalysisBlock:295-301`): (1) anchorKey → enclosing `{` → depth-match `}`; (2) outermost `{...}`; (3) lenient parse `ALLOW_UNESCAPED_CONTROL_CHARS`; (4) весь текст. `AgentVerifyBlock.parseFinalJson` → тонкий делегат (без изменения вызовов).
**Тесты:** `JsonExtractorTest` — портировать edge-cases (фигурные скобки в evidence-строках, проза-обёртка, code-fence, неэкранированные control-chars, anchor-not-found → last `{}`).
**Риск: НИЗКИЙ** (behavior-preserving).

### 2B. `responseFormat` в single-shot пути (ПЕРЕГРУЗКА, не смена сигнатуры)
НЕ менять существующую 5-арг `complete()` (≈14 вызовов + интерфейс). Добавить overload:
```java
// LlmClient
String complete(String model, String system, String user, int maxTokens, double temperature, String responseFormat)
// старая 5-арг → делегирует с responseFormat=null

// LlmProviderClient (default-метод для backwards-compat)
default String complete(..., String responseFormat) { return complete(...); }
```
Пробросить в `OllamaProviderClient.chat()` и vLLM single-shot путь тот же `format:"json"` / `response_format:{type:json_object}`, что уже применяется в tool-use пути. OpenRouter/AITunnel/AllTokens (OpenAI-совместимы) — добавить `response_format` в body. CLI — игнор (нет JSON-флага, полагаемся на промпт+парсер).
**Критично:** overload только для single-shot (Analysis/CodeGen/Verify, без tools). Tool-use путь (`completeWithTools` + `ToolUseRequest.responseFormat`) не трогается → правило «не форсить json на Ollama tool_calls» (`OrchestratorBlock:1165`) структурно невозможно нарушить.

### 2C. Применить JSON-режим + общий парсер в трёх блоках

| Блок | Правка | Риск |
|---|---|---|
| `AnalysisBlock:289` | `responseFormat="json"`; заменить инлайн-парсер на `JsonExtractor.extractObject(resp, "summary", mapper)`; сохранить `putIfAbsent`-дефолты | НИЗКИЙ |
| `CodeGenerationBlock:280-292` | `"json"`; **фикс потери данных**: вместо голого `continue` — retry через `JsonExtractor`, при неудаче placeholder-задача + счётчик `parse_failures` (не терять молча, но и не бросать — run завершается) | СРЕДНИЙ |
| `VerifyBlock:186-191` | `"json"`; `readValue` → `JsonExtractor.extractObject(resp, "score", mapper)`; при полном провале парсинга — fail-safe `score=null, recommendation="parse_failed"`, не PASS, без проброса Exception | СРЕДНИЙ |

**Тесты:** `AnalysisBlockTest` (recovered JSON + дефолты), `CodeGenerationBlockTest` (битая задача не исчезает молча, остальные обрабатываются), `VerifyBlockTest` (parse fail → not-passed, без Exception), provider-тесты (overload ставит `format`/`response_format`, старая 5-арг не изменилась).
**Риск: СРЕДНИЙ** (две поведенческие правки в CodeGen/Verify), плюс LOW на плумбинг.

---

## Кластер 3 — Рантайм-устойчивость

### 3A. Объединённый бюджет итераций (loopback + escalation)
Добавить единый per-run счётчик `PipelineRun.totalRemediationIterations` (новое поле, default 0), инкремент в `handleLoopback` (`PipelineRunner:854`) И в путях инкремента `EscalationService`. Проверять против `EscalationProperties.maxTotalIterations` (default 6). При достижении — `handleLoopback` возвращает -1, `attemptEscalation` → `exhausted("global_iteration_cap")`. Локальные счётчики остаются (бьют первыми при меньшем лимите); глобальный — верхняя огибающая против мультипликативного прожига.
**Тесты:** глобальный cap стопит цепочку при остатке локальных бюджетов; локальный cap срабатывает первым при меньшем значении.
**Риск: СРЕДНИЙ** (щедрый дефолт + тесты на взаимодействие).

### 3B. NoProgressDetector always-on
`PipelineRunner:841` пропускает детектор при `null`. Сделать его жёсткой зависимостью конструктора в production-проводке, null-guard оставить только для unit-тестов. Проверить, что `@Autowired`-поле реально заполнено; добавить startup-лог/assert.
**Риск: НИЗКИЙ.**

### 3C. Обрезка `loop_history_json`
После `history.add(entry)` (`PipelineRunner:890`) ограничить список последними N (≈50), то же в `appendNoProgressHistoryEntry`. Убедиться, что `extractPriorIssues:844` нужны только недавние записи (N >> числа итераций на цикл).
**Тесты:** N+ итераций → длина истории ограничена, no-progress-детекция работает на усечённой истории.
**Риск: НИЗКИЙ.**

### 3D. Новые метрики (`PipelineMetrics`)
По образцу существующих `Counter.builder`:
- `workflow_loopback_iterations_total{block,target}` (в `handleLoopback`)
- `workflow_no_progress_detections_total{block}` (в stuck-ветке)
- `workflow_escalation_steps_total{tier}` (в cloud/human ветках `EscalationService`)
- `workflow_remediation_iterations` (глобальный счётчик из 3A)
- `workflow_llm_tokens_wasted_total` (токены итераций, отброшенных loopback/no-progress; переиспользовать `recordLlmTokens`)

**Тесты:** инкремент счётчиков через `SimpleMeterRegistry`. **Риск: НИЗКИЙ** (чисто аддитивно).

### 3E. Lenient-интерполяция `inject_context`
`StringInterpolator` сейчас fail-loud при unresolved `${...}`. Для пути `resolveInjectContext` (`PipelineRunner:1440`) добавить флаг `lenient`: unresolved ref → WARN-лог + пустая подстановка (отсутствие feedback-поля не должно ронять весь run). Все остальные пути интерполяции остаются строгими.
**Тесты:** inject_context с missing field → warn + пусто + run продолжается; config-level missing ref → по-прежнему throws.
**Риск: СРЕДНИЙ** (ослабление fail-loud; митигировано тем, что Кластер 1 ловит большинство на этапе конфига, WARN-лог, и узкий scope только inject_context).

### 3F. Per-block таймаут на вызов блока
В `runWithRetry` (`PipelineRunner:1759`) обернуть `block.run(...)` в `ExecutorService.submit(...).get(timeout, SECONDS)`; источник — `effectiveConfig.getTimeoutSeconds()` (уже валидируется Кластером 1). `TimeoutException` трактуется как retryable (считается в `max_attempts`), `future.cancel(true)` + проброс `InterruptedException` (retry-цикл уже спец-обрабатывает его на `:1771`).
**Backwards-compat:** включать только при `timeoutSeconds > 0` И за opt-in property `workflow.runtime.block-timeout-enabled` (default **OFF** один релиз). Аудит того, что tool-use/CLI блоки с подпроцессами честно реагируют на interrupt.
**Тесты:** блок-долгожитель → TimeoutException + retry + чистый fail без утечки потоков; блок в пределах таймаута не затронут; property OFF → таймаута нет.
**Риск: ВЫСОКИЙ** (cancellation потоков в hot-path). Делать последним, за флагом.

---

## Последовательность (PR-ы)

| PR | Содержание | Приоритет | Риск |
|---|---|---|---|
| PR-1 | Кластер 1 — дыры валидатора | HIGH | LOW |
| PR-2 | 2A — `JsonExtractor` + рефактор `AgentVerifyBlock` | HIGH | LOW |
| PR-3 | 2B — overload `responseFormat` через провайдеры | HIGH | LOW |
| PR-4 | 2C — JSON-режим + парсер + фиксы в Analysis/CodeGen/Verify | HIGH | MEDIUM |
| PR-5 | 3A+3B+3C+3D — бюджет итераций, NoProgress, обрезка истории, метрики | MEDIUM | LOW–MEDIUM |
| PR-6 | 3E — lenient inject_context | MEDIUM | MEDIUM |
| PR-7 | 3F — per-block timeout (opt-in OFF) | MEDIUM | HIGH |

PR-2/3 — предпосылки PR-4. PR-6 опирается на PR-1 (валидатор ловит остальное на конфиге).

## Критические файлы
- `workflow-core/src/main/java/com/workflow/config/PipelineConfigValidator.java` (+ внедрить `ModelPresetResolver`)
- `workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java` (+ `knownTierKeys()`)
- `workflow-core/src/main/java/com/workflow/llm/LlmClient.java` + `llm/provider/LlmProviderClient.java` + `OllamaProviderClient.java` / `VllmProviderClient.java` / OpenRouter-провайдер
- `workflow-core/src/main/java/com/workflow/llm/JsonExtractor.java` (новый, источник — `blocks/AgentVerifyBlock.java:456-491`)
- `workflow-core/src/main/java/com/workflow/blocks/{AnalysisBlock,CodeGenerationBlock,VerifyBlock}.java`
- `workflow-core/src/main/java/com/workflow/core/{PipelineRunner,NoProgressDetector,EscalationService,EscalationProperties}.java` + `core/PipelineRun.java` (новое поле) + `core/expr/StringInterpolator.java`
- `workflow-core/src/main/java/com/workflow/observability/PipelineMetrics.java`

## Верификация
- `cd workflow-core && gradle build` — unit + IT (ITs гейтятся `OPENROUTER_API_KEY`, дефолтный прогон герметичен).
- Точечно: `gradle test --tests 'com.workflow.config.PipelineConfigValidatorTest'`, `*JsonExtractorTest`, `*CodeGenerationBlockTest`, `*VerifyBlockTest`, escalation/loopback тесты.
- Регрессия: прогнать существующий рабочий `config/pipeline.example.yaml` через `POST /api/pipelines/validate` — 0 ERROR.
- E2E: запустить реальный пайплайн на vLLM/Ollama (qwen3-4b) и на OpenRouter — убедиться, что codegen/verify не падают на кривом JSON, метрики loopback/escalation видны в `/actuator/prometheus`.
- После PR-7: включить `workflow.runtime.block-timeout-enabled=true` на одном прогоне, проверить отсутствие утечки потоков и корректный retry по таймауту.

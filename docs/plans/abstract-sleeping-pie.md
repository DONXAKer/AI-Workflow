# Pipeline Improvement Plan — Quality, Security, Validation, Observability

## Context

Анализ выполненных флоу по четырём осям: качество выполнения, безопасность, валидация конфигов, наблюдаемость. Цель — закрыть молчаливые сбои, дать оператору видимость и предотвратить деградацию до ручного разбора логов.

Исходные данные: статический анализ кода + результаты прогонов пайплайнов. Три агента покрыли execution engine, security layer и observability/ops.

---

## SECURITY — критические правки (делать первыми)

### S-1: Токены интеграций утекают в API-ответы
**Симптом:** `IntegrationConfig.token` не имеет `@JsonIgnore`. `GET /api/integrations` и `GET /api/integrations/{id}` возвращают расшифрованный токен в JSON-ответе.  
**Файл:** `model/IntegrationConfig.java` — добавить `@JsonIgnore` на метод `getToken()`.  
**Усилие:** XS

### S-2: `findByType()` / `findByName()` не скоупятся по аккаунту
**Симптом:** `IntegrationConfigRepository.findByType(IntegrationType)` и `findByName(String)` возвращают записи всех аккаунтов. Tenant A может прочитать интеграции Tenant B, если угадает имя.  
**Файлы:**  
- `model/IntegrationConfigRepository.java` — добавить `accountId`-параметрные варианты и убрать вызовы без него из `TaskMdInputBlock`, `IntegrationConfigMigrator`, `EntryPointResolver`.  
- Или добавить Hibernate-фильтр аналогично `@Filter(name = "accountFilter")` с `@FilterDef` на entity — тогда запросы автоматически скоупятся.  
**Усилие:** M

### S-3: DenyList не блокирует `python -c`, `perl -e`, `base64 | sh`, `curl | sh`
**Симптом:** Команды вида `python3 -c "import os; os.system('...')"` в `shell_exec`/`BuildBlock` не заблокированы.  
**Файл:** `tools/DenyList.java` — добавить паттерны: `python.*-c`, `perl.*-e`, `node.*-e`, `ruby.*-e`, `base64.*\|.*sh`, `curl.*\|.*sh`, `wget.*\|.*sh`, `eval\s*\(`.  
**Усилие:** S

### S-4: Ключ шифрования с fallback на хардкод
**Симптом:** Если `workflow.encryption.key` не задан, `AesSecretStore` использует захардкоденный dev-ключ с WARNING в логах. В prod-деплоях без настройки env-переменной все токены шифруются одним ключом.  
**Файл:** `security/AesSecretStore.java` — заменить fallback на `throw new IllegalStateException("workflow.encryption.key must be set in production")`. Добавить health-check или startup validation.  
**Усилие:** S

### S-5: H2 console открыта без prod-guard
**Симптом:** `SecurityConfig` открывает `/h2-console/**` без профильного ограничения.  
**Файл:** `security/SecurityConfig.java` — оборачивать правило в `@Profile("dev")` или добавить `@ConditionalOnProperty(name="spring.h2.console.enabled")`.  
**Усилие:** XS

---

## TIER 1 — Высокий импакт, малые усилия (≤1 дня каждая)

### T1-1: Плохой синтаксис condition → ошибка, не молчаливое `true`
**Симптом:** `PipelineRunner.evaluateClause()` возвращает `true` при parse failure — guard становится бесполезным. `$.x>=0.8` (пропущен пробел) молча пропускает все блоки.  
**Файл:** `core/PipelineRunner.java` — в catch-блоке `evaluateClause()` бросать `ConditionSyntaxException` вместо `return true`.  
`config/PipelineConfigValidator.java` — расширить `checkExprSyntax()` на `condition:` поля всех блоков.  
**Усилие:** S

### T1-2: `REF_UNKNOWN_FIELD` повысить с WARN до ERROR
**Симптом:** `${analysis.typo_field}` проходит валидацию с предупреждением. В рантайме `null` тихо попадает в промпт.  
**Файл:** `config/PipelineConfigValidator.java:1043` — `Severity.WARN` → `Severity.ERROR` для `REF_UNKNOWN_FIELD`.  
**Усилие:** XS

### T1-3: Включить block execution timeout
**Симптом:** `workflow.runtime.block-timeout-enabled=false` — реализация готова (`PipelineRunner.java:1849-1906`), флаг не включён. Зависший LLM-вызов держит поток вечно.  
**Файл:** `application.yaml` — `workflow.runtime.block-timeout-enabled: true`. Верифицировать что `ClaudeCodeShellBlock` и `prodDeployMutex.acquire()` корректно обрабатывают `InterruptedException`.  
**Усилие:** XS

### T1-4: CI timeout/no_runs → статус `failure`
**Симптом:** `GitHubActionsBlock` возвращает `"no_runs"` вместо `"failure"` если workflow не найден или истёк таймаут. Pipeline продолжается как будто CI прошёл.  
**Файлы:**  
- `blocks/GitHubActionsBlock.java`, `calculateOverallStatus()` — `no_runs` → `"failure"`.  
- `blocks/GitLabCIBlock.java` — `timeout` → `"failed"`.  
**Усилие:** S

### T1-5: Уведомление при падении блока
**Симптом:** Только approval-pause триггерит Slack/Telegram. Жёсткие сбои (LLM-таймаут, CI-ошибка) не алертят оператора.  
**Файл:** `core/PipelineRunner.java`, `markFailed()` — добавить вызов `broadcastFailureNotification()` по аналогии с `broadcastApprovalNotification()` (line 1908).  
**Усилие:** S

### T1-6: Rate limiting уведомлений
**Симптом:** 100 loopback-итераций = 100 Slack-сообщений. Оператор глушит канал.  
**Файл:** Новый `notifications/NotificationRateLimiter.java` — `ConcurrentHashMap<runId+blockId, Instant> lastSentAt`, cooldown 5 мин (`workflow.notifications.cooldown-minutes`). `NotificationChannelRegistry` проверяет перед отправкой.  
**Усилие:** S

### T1-7: Qdrant: фильтр по similarity score
**Симптом:** `QdrantKnowledgeBase.search()` возвращает все K хитов без порогового фильтра. Score 0.1 и 0.9 попадают в промпт вместе.  
**Файл:** `knowledge/QdrantClient.java`, `search()` — добавить `body.put("score_threshold", minScore)`. `QdrantKnowledgeBase.java` — `@Value("${workflow.knowledge.similarity-threshold:0.5}")`.  
**Усилие:** S

### T1-8: Qdrant: retry на сетевой блип
**Симптом:** Один 30s-таймаут, no retry. Один сетевой сбой = 0 контекста для всего запроса.  
**Файл:** `knowledge/QdrantClient.java` — `Retry.fixedDelay(2, Duration.ofMillis(500))` на Mono перед `.block()`. Не ретраить 4xx.  
**Усилие:** XS

---

## TIER 2 — Средний импакт или большие усилия

### T2-1: Структурированная причина сбоя на PipelineRun
**Зачем:** Сейчас `PipelineRun.error` — одна строка. Нельзя запросить «все раны упавшие по LLM-таймауту».  
**Изменения:**  
- `core/PipelineRun.java` — добавить поле `FailureCategory failureCategory` (enum: `TIMEOUT, VALIDATION, DEPENDENCY_MISSING, LLM_ERROR, CI_FAILURE, INTEGRATION_ERROR, CANCELLED, UNKNOWN`).  
- `core/PipelineRunner.java`, `markFailed()` — `classifyFailure(message, cause)` по типу исключения и паттернам строки.  
- `observability/PipelineMetrics.java`, `recordBlockFailed()` — добавить тег `reason`.  
- DB: `ALTER TABLE pipeline_run ADD COLUMN failure_category VARCHAR(32)`.  
**Усилие:** M

### T2-2: Fail-fast при отсутствии вывода upstream-блока
**Зачем:** `gatherInputs()` подставляет `{}` вместо вывода, если upstream-блок не записал output. Блок падает позже с непонятной ошибкой.  
**Файл:** `core/PipelineRunner.java:529` — `log.warn + inputs.put(depId, {})` → `throw new IllegalStateException("Block X requires output from Y but none was found")`.  
**Усилие:** S (Tier 2 из-за возможного срабатывания на существующих пайплайнах — нужен постепенный rollout)

### T2-3: Обнаружение циклов в loopback-графе
**Зачем:** A→B loopback + B→A loopback проходит валидацию (DAG проверяется только по `depends_on`). В рантайме оба петляют до исчерпания лимита.  
**Файл:** `config/PipelineConfigValidator.java` — метод `detectLoopbackCycles()`: построить граф `blockId → loopback_target` из всех `verify.on_fail.target` и `on_failure.target`; DFS по аналогии с `detectCycles()`; новый код `LOOPBACK_CYCLE`, `Severity.ERROR`.  
**Усилие:** M

### T2-4: LLM latency histogram + cost-per-project
**Зачем:** `LlmCall.durationMs` сохраняется, но не подаётся в Prometheus. Нет p95 по моделям, нет стоимости по проекту.  
**Файлы:**  
- `observability/PipelineMetrics.java` — `recordLlmLatency(model, Duration)` (Timer) + `recordLlmCostCents(projectSlug, costUsd)` (Counter в центах).  
- `llm/provider/OpenAICompatibleProviderClient.java`, `recordUsage()` — вызывать оба новых метода.  
**Усилие:** M

### T2-5: Уведомление о climb escalation ladder
**Зачем:** Когда `tryEscalateAfterLoopback()` поднимает блок на cloud-tier, оператор не знает об этом до human-gate.  
**Файл:** `core/PipelineRunner.java`, ветка `EscalationDecision.RetryWithCloud` — добавить `broadcastEscalationNotification()` после `metrics.recordEscalationStep("cloud")`.  
**Усилие:** S (зависит от T1-5 + T1-6)

### T2-6: Disk quota для workspaces
**Зачем:** Нет лимита на суммарный объём клонов. 50 одновременных прогонов большого репо = десятки ГБ.  
**Файлы:**  
- `workspace/WorkspaceProperties.java` — `maxTotalMb`, `maxPerAccountMb`.  
- `workspace/WorkspaceProvisioner.java`, `provision()` — проверять `Files.walk()` до клона.  
**Усилие:** M

### T2-7: Fallback на project.workingDir при неудаче клона
**Зачем:** `WorkspaceProvisioningException` сразу отдаёт HTTP 400, даже если у проекта есть локальный `workingDir`.  
**Файл:** `api/RunController.java`, catch `WorkspaceProvisioningException` — если `project.workingDir != null`, продолжать без `_workspaceDir` (с аудит-варнингом).  
**Усилие:** S

### T2-8: Сохранять retry-попытки в BlockOutput
**Зачем:** Retry в памяти. Рестарт JVM сбрасывает счётчик — аудит-след теряется.  
**Файлы:**  
- `core/BlockOutput.java` — поля `retryAttempt INT`, `retryError TEXT`.  
- `core/PipelineRunner.java`, `runWithRetry()` — `blockOutputRepository.save(...)` на каждый catch.  
- DB migration.  
**Усилие:** M

---

## TIER 3 — Архитектурные улучшения (долгосрочно)

### T3-1: `GET /api/pipelines/schema` — JSON Schema для YAML
Pipeline YAML не имеет машиночитаемой схемы. IDE не может автодополнять и валидировать. Написать `src/main/resources/pipeline-schema.json` и отдавать через `PipelineController`. Подключить к Monaco-редактору во фронте.  
**Усилие:** L

### T3-2: `OutputValidator` — enum и range проверки
Добавить `EnumCheck` (field + allowed values) и `RangeCheck` (min/max для числовых полей) в `OutputValidationConfig` и `OutputValidator.java`. Без JSON Schema-библиотек.  
**Усилие:** M

### T3-3: `GET /api/runs/failure-summary` — аналитика по FailureCategory
Зависит от T2-1. JPQL GROUP BY query в `PipelineRunRepository`, новый endpoint в `RunController`. Маленький breakdown-чарт во фронте.  
**Усилие:** M (после T2-1)

### T3-4: `BlockEvent` таблица — полный timeline блока
Выделенная таблица `block_event` (`run_id`, `block_id`, `event_type`, `attempt`, `message`, `occurred_at`) вместо ad-hoc логов и retry-полей в BlockOutput. Заменяет T2-8 если будет принято решение о более полной аудит-таблице.  
**Усилие:** L

---

## Сводная таблица приоритетов

| # | Улучшение | Критерий | Усилие | Зависит |
|---|-----------|----------|--------|---------|
| S-1 | `@JsonIgnore` на токены интеграций | **Security** | XS | — |
| S-2 | Multi-tenant isolation для findByType/findByName | **Security** | M | — |
| S-3 | DenyList: python/perl/curl/base64 | **Security** | S | — |
| S-4 | Encryption key fallback → hard fail | **Security** | S | — |
| S-5 | H2 console: dev-only | **Security** | XS | — |
| T1-1 | Condition syntax error → исключение | **Quality** | S | — |
| T1-2 | REF_UNKNOWN_FIELD → ERROR | **Validation** | XS | — |
| T1-3 | Включить block timeout | **Quality** | XS | — |
| T1-4 | CI no_runs/timeout → failure | **Quality** | S | — |
| T1-5 | Block failure notification | **Observability** | S | — |
| T1-6 | Notification rate limiting | **Observability** | S | T1-5 |
| T1-7 | Qdrant score_threshold | **Quality** | S | — |
| T1-8 | Qdrant retry | **Quality** | XS | — |
| T2-1 | FailureCategory + метрика reason | **Observability** | M | — |
| T2-2 | gatherInputs fail-fast | **Quality** | S | T1-2 |
| T2-3 | Loopback cycle detection | **Validation** | M | — |
| T2-4 | LLM latency + cost metrics | **Observability** | M | — |
| T2-5 | Escalation step-up notification | **Observability** | S | T1-5,T1-6 |
| T2-6 | Workspace disk quota | **Ops** | M | — |
| T2-7 | Clone fallback to workingDir | **Quality** | S | — |
| T2-8 | Retry audit в BlockOutput | **Quality** | M | — |
| T3-1 | Pipeline JSON Schema endpoint | **Validation** | L | — |
| T3-2 | OutputValidator enum/range | **Validation** | M | — |
| T3-3 | Failure summary API | **Observability** | M | T2-1 |
| T3-4 | BlockEvent audit table | **Quality** | L | T2-8 |

---

## Верификация

После каждой Tier 1/2 правки:
1. `gradle test` под JDK 21 — все 482 теста зелёные.
2. Для S-2 (multi-tenant): интеграционный тест, создающий две записи под разными `accountId` и проверяющий что `findByType()` не возвращает чужую.
3. Для T1-1 (condition syntax): unit-тест `PipelineRunner` с условием `$.x>=0.8` (без пробела) — ожидать исключение, не `true`.
4. Для T1-4 (CI timeout): mock `GitHubClient.getWorkflowRuns()` → пустой список — проверить `overall_status = "failure"`.
5. Для T1-7 (Qdrant threshold): mock Qdrant, вернуть хиты с score 0.3 и 0.7 — при threshold 0.5 должен вернуться только второй.
6. Security S-1: `GET /api/integrations` — в теле ответа поле `token` должно отсутствовать или быть `null`.

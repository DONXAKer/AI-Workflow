# Post-mortem SM-003 + оптимизация пайплайна

## Context

Задача SM-003 (вкладка «Промпт» в InstallTabs) не смогла завершиться автоматически через пайплайн:
- GLM Flash (`z-ai/glm-4.7-flash`) не исправил баги за 2 loopback-итерации
- `code_only` entry point падал мгновенно с `PathNotFoundException`
- Потребовался внешний Python-скрипт для авто-апрува + ручные правки кода

Цель этого плана: устранить пять структурных проблем чтобы пайплайн работал автономно.

---

## Анализ последнего пайплайна (698970ec)

**Запуск:** 2026-05-01 20:55–20:56 UTC, 44 секунды, $0.66  
**Блоки:** task_md → analysis → clarification → plan → codegen → build_test → review  
**Модели:** analysis/codegen/clarification = `smart` (claude-sonnet-4-6), plan/review = `reasoning` (claude-opus-4-7)  
**Review: `passed: true`, `action: continue`, 8 LLM-итераций**

Review нашёл и **не заблокировал**:
- Frontend build сбоит из-за отсутствия `lightningcss.darwin-arm64.node` — env-проблема, не код
- `installAnalyticsService.logInstall()` вызывается при каждом открытии таба Промпт (не install)
- Два S3 GET вместо одного в `getSkillPrompt`

Review подтвердил:
- API компиляция ✅, тесты покрывают happy/401/404 пути ✅
- SecurityConfig корректно требует auth для `/prompt` ✅

**Проблема в этом запуске:** он запустился с `fromBlock=build_test` + ручными injected outputs — потому что `code_only` entry point сломан (Проблема 1 ниже). Это обходной путь, не нормальный флоу.

---

## Проблема 1 — PathNotFoundException в `code_only` (БЛОКИРУЮЩАЯ)

### Симптом
`entryPointId=code_only` падает мгновенно: `no output for block 'plan' — completed blocks: []`

### Root cause
`PipelineRunner.runFrom()`, строки ~173-188:
```java
if (!injected.isEmpty()) {   // ← пустая map = BlockOutput НЕ сохраняется
    saveBlockOutput(run, blockConfig.getId(), injected);
}
```
`source=empty` возвращает `new HashMap<>()` → блок попадает в `completedBlocks` но не в `run.getOutputs()`. `PathResolver.loadBlockOutput("plan")` ищет в `outputs` → не находит → `PathNotFoundException`.

### Исправление
**Файл:** `src/main/java/com/workflow/core/PipelineRunner.java`

Убрать условие `!injected.isEmpty()`, всегда сохранять BlockOutput (пустой `{}`):

```diff
- if (!injected.isEmpty()) {
-     String outputJson = objectMapper.writeValueAsString(injected);
-     pipelineRun.getOutputs().add(BlockOutput.builder()
-         .run(pipelineRun).blockId(blockConfig.getId()).outputJson(outputJson).build());
- }
+ try {
+     String outputJson = objectMapper.writeValueAsString(injected); // "{}" для source=empty
+     pipelineRun.getOutputs().add(BlockOutput.builder()
+         .run(pipelineRun).blockId(blockConfig.getId()).outputJson(outputJson).build());
+ } catch (Exception e) {
+     log.warn("Failed to serialize injected output for block {}: {}", blockConfig.getId(), e.getMessage());
+ }
```

Дополнительно улучшить сообщение в `PathResolver.java` строка ~87:
```diff
- "no output for block '" + blockId + "' — completed blocks: "
-     + outs.stream().map(BlockOutput::getBlockId).toList()
+ "no output for block '" + blockId + "' — outputs in DB: "
+     + outs.stream().map(BlockOutput::getBlockId).toList()
+     + " (hint: entry-point inject must cover all blocks before fromBlock)"
```

### Верификация
1. POST `/api/runs` с `entryPointId=code_only` + `inputs.requirement=...`
2. Run должен дойти до `codegen` блока (не упасть при старте)
3. `${plan.goal}` резолвится в `""` (пустая строка из пустого BlockOutput)

---

## Проблема 2 — Loopback feedback теряет приоритет

### Симптом
GLM Flash при loopback делал те же ошибки — review-контекст не доходил до модели с достаточным весом.

### Root cause
`AgentWithToolsBlock.appendLoopbackFeedback()` добавляет retry-контекст в **конец** `user_message`. LLM отдаёт приоритет началу промпта. `retry_instruction` и `issues` из `inject_context` не выделены как заголовки.

### Исправление
**Файл:** `src/main/java/com/workflow/blocks/AgentWithToolsBlock.java`

Переименовать `appendLoopbackFeedback` → `prependLoopbackFeedback`, вставлять **перед** основным текстом:

```java
private static String prependLoopbackFeedback(String userMessage, Map<String, Object> input) {
    Object raw = input.get("_loopback");
    if (!(raw instanceof Map<?, ?> loopback) || loopback.isEmpty()) return userMessage;

    StringBuilder sb = new StringBuilder();
    sb.append("## ВАЖНО: Повторная попытка (итерация ").append(loopback.get("iteration")).append(")\n\n");
    sb.append("Предыдущая реализация НЕ прошла проверку. Исправь проблемы ПЕРЕД выполнением задачи:\n\n");

    Object ri = loopback.get("retry_instruction");
    if (ri != null && !ri.toString().isBlank()) {
        sb.append("### Инструкция от ревьюера\n").append(ri).append("\n\n");
    }

    Object issues = loopback.get("issues");
    if (issues instanceof List<?> list && !list.isEmpty()) {
        sb.append("### Проблемы для исправления\n");
        list.forEach(item -> sb.append("- ").append(item).append('\n'));
        sb.append('\n');
    }

    Object bo = loopback.get("build_output");
    if (bo != null && !bo.toString().isBlank()) {
        String boStr = bo.toString();
        sb.append("### Вывод сборки\n```\n")
          .append(boStr.length() > 2000 ? boStr.substring(boStr.length() - 2000) : boStr)
          .append("\n```\n\n");
    }

    sb.append("---\n\n## Основная задача\n\n").append(userMessage);
    return sb.toString();
}
```

Изменить вызов в `run()`:
```diff
- String userMessage = appendLoopbackFeedback(interpolate(expanded, input), input);
+ String userMessage = prependLoopbackFeedback(interpolate(expanded, input), input);
```

### Верификация
1. Запустить run с заведомо неправильным кодом → review выдаёт retry
2. В логе AgentWithToolsBlock второй вызов начинается с `## ВАЖНО: Повторная попытка`
3. Обновить assertions в `AgentWithToolsBlockTest`

---

## Проблема 3 — Нет встроенного auto-approve

### Симптом
Пришлось писать внешний Python-скрипт с управлением cookie. Скрипт терял сессию, пропускал PAUSED_FOR_APPROVAL события.

### Решение A: флаг `autoApproveAll` в API (основное)

**Файл:** `src/main/java/com/workflow/api/RunController.java`

В `startRun()` добавить:
```java
boolean autoApproveAll = Boolean.TRUE.equals(request.get("autoApproveAll"));
```

В `PipelineRunner.run()` / `runFrom()` после создания `PipelineRun`:
```java
if (namedInputs != null && Boolean.TRUE.equals(namedInputs.get("_autoApproveAll"))) {
    pipelineRun.getAutoApprove().add("*");
}
```

В `RunController.startRun()` передавать через namedInputs:
```java
if (autoApproveAll) namedInputs.put("_autoApproveAll", true);
```

Механизм `run.getAutoApprove().contains("*")` уже есть в `executeBlocks()` (строка ~778).

### Решение B: `action: approve` при timeout

**Файл:** `src/main/java/com/workflow/config/TimeoutConfig.java`
```diff
  public enum Action {
      FAIL("fail"), NOTIFY("notify"), ESCALATE("escalate"),
+     APPROVE("approve");
  }
```

**Файл:** `src/main/java/com/workflow/core/ApprovalTimeoutScheduler.java`
```java
private void applyApprove(PipelineRun run, String blockId, Duration elapsed) {
    log.info("Auto-approving block '{}' for run {} after timeout ({}s)", blockId, run.getId(), elapsed.getSeconds());
    run.setPausedAt(null);
    run.setApprovalTimeoutSeconds(null);
    run.setApprovalTimeoutAction(null);
    runRepository.save(run);
    webSocketApprovalGate.resolveApproval(blockId,
        ApprovalResult.builder().status("APPROVED").output(new HashMap<>()).build());
}
```

### Верификация
1. POST `/api/runs` с `{"autoApproveAll": true, ...}` → пайплайн не останавливается на approval
2. Блок с `timeout: 10, on_timeout.action: approve` → через ~60 сек авто-апрув (scheduler ticks каждые 60 сек)

---

## Проблема 4 — YAML: все блоки требуют ручного апрува

### Изменение в `config/skill_marketplace.yaml`

Неинтерактивные блоки переключить на `auto_notify` (уведомляет, не останавливает):

```yaml
- id: analysis
  approval_mode: auto_notify  # было: approval: true
  timeout: 3600
  on_timeout:
    action: approve

- id: clarification
  approval_mode: auto_notify  # было: approval: true

- id: codegen
  approval_mode: auto_notify  # было: approval: true

- id: plan
  approval: true               # ОСТАВИТЬ — требует человека

# build_test и review уже approval: false — не менять
```

Та же правка в `skill_marketplace/.ai-workflow/pipelines/pipeline.yaml`.

### Верификация
Запуск `from_requirement` → только `plan` создаёт `PAUSED_FOR_APPROVAL`, остальные блоки идут с `AUTO_NOTIFY` событиями.

---

## Проблема 5 — build_test: exit code и max_iterations

### 5a. POSIX sh + `| tail` скрывает exit code

Команда `./gradlew ... 2>&1 | tail -20` — в POSIX sh exit code = код `tail` (0), не `gradlew`. ShellExecBlock получает `exit_code=0` даже при ошибке компиляции.

**Исправление в yaml:**
```yaml
command: |
  bash -c '
    set -eo pipefail
    echo "=== API compilation ==="
    cd /Users/home/Code/skill_marketplace/api
    ./gradlew compileJava compileTestJava --no-daemon -q 2>&1 | tail -20
    echo "=== Frontend build ==="
    cd /Users/home/Code/skill_marketplace/web
    npm run build 2>&1 | tail -30
    echo "=== DONE ==="
  '
```

С `set -eo pipefail` в bash: exit code pipe = exit code упавшей команды.

### 5b. max_iterations: 2 → 3

```yaml
verify:
  on_fail:
    action: loopback
    target: codegen
    max_iterations: 3   # было: 2
```

### Верификация
1. Внести синтаксическую ошибку в TypeScript файл → `build_test` должен вернуть `success: false`
2. Три loopback-итерации должны исчерпываться перед FAILED статусом

---

## Порядок внедрения

### Фаза 1 — Немедленно (блокирующая)
- Fix `PipelineRunner.runFrom()` — убрать `!injected.isEmpty()`
- Улучшить сообщение в `PathResolver.java`

После этого `code_only` entry point будет работать.

### Фаза 2 — Параллельно

**Группа A** (Java, ~2 часа):
- `AgentWithToolsBlock.appendLoopbackFeedback` → `prependLoopbackFeedback`

**Группа B** (Java, ~3 часа):
- `RunController`: флаг `autoApproveAll`
- `PipelineRunner.run()` / `runFrom()`: применение `autoApproveAll`
- `TimeoutConfig.Action`: добавить `APPROVE`
- `ApprovalTimeoutScheduler`: метод `applyApprove`

### Фаза 3 — YAML (после Фазы 2, без перекомпиляции)
- `skill_marketplace.yaml`: approval_mode для неинтерактивных блоков
- `skill_marketplace.yaml`: bash pipefail в build_test
- `skill_marketplace.yaml`: max_iterations: 3
- Синхронизировать с `.ai-workflow/pipelines/pipeline.yaml`

---

## Критические файлы

| Файл | Проблема |
|------|---------|
| `src/main/java/com/workflow/core/PipelineRunner.java` | Fix 1 (runFrom), Fix 3 (autoApproveAll в run/runFrom) |
| `src/main/java/com/workflow/blocks/AgentWithToolsBlock.java` | Fix 2 (prependLoopbackFeedback) |
| `src/main/java/com/workflow/core/ApprovalTimeoutScheduler.java` | Fix 3b (applyApprove) |
| `src/main/java/com/workflow/config/TimeoutConfig.java` | Fix 3b (Action.APPROVE) |
| `src/main/java/com/workflow/api/RunController.java` | Fix 3a (autoApproveAll flag) |
| `src/main/java/com/workflow/core/expr/PathResolver.java` | Fix 1 (улучшить сообщение) |
| `workflow-core/config/skill_marketplace.yaml` | Fix 4 (approval_mode), Fix 5 (pipefail, max_iterations) |
| `skill_marketplace/.ai-workflow/pipelines/pipeline.yaml` | Синхронизация с выше |

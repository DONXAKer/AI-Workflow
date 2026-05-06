# UI-002 Хронологический порядок блоков и продолжительность в RunPage

**Блок:** Backend schema + UI display
**Приоритет:** Высокий

## Цель
На странице run-detail (`/runs/{id}` и `/projects/:slug/runs/{id}`) показать события выполнения блоков **в хронологическом порядке выполнения** и **продолжительность каждого блока**. Сейчас порядок перепутан и duration отсутствует.

## Корневая причина (диагностика уже сделана)

1. `workflow-core/src/main/java/com/workflow/core/PipelineRun.java`:
   - Поле `completedBlocks` имеет тип `Set<String>` (`HashSet<>()`) → JPA сохраняет в `pipeline_run_completed_blocks`, но **порядок не гарантирован** (HashSet → hash-order). UI берёт этот список и рендерит как timeline → перепутано.
2. `workflow-core/src/main/java/com/workflow/core/BlockOutput.java`:
   - Сущность хранит только `id, blockId, outputJson, runId, inputJson` — **нет `startedAt` / `completedAt` колонок**. Поэтому duration невычислима.
3. `RunController.java` — endpoint `/api/runs/{id}/blocks` (если есть) возвращает пусто, потому что нет таблицы с per-block timings.

## Что сделать

### 1. `BlockOutput.java` — добавить timestamps

Добавить два поля:
```java
@Column(name = "started_at")
private Instant startedAt;

@Column(name = "completed_at")
private Instant completedAt;

// + геттеры/сеттеры; обновить Builder
```

Spring Boot `ddl-auto: update` автоматически сделает `ALTER TABLE block_output ADD COLUMN ...` при перезапуске.

### 2. `PipelineRunner.java` — проставлять timestamps

Найти место, где блок выполняется (вызов `block.run(input, config, run)`) и где сохраняется `BlockOutput`. Перед вызовом — `Instant start = Instant.now()`. После — `BlockOutput.setStartedAt(start); setCompletedAt(Instant.now());` перед `blockOutputRepository.save(...)`.

### 3. `PipelineRun.completedBlocks` — упорядочить

Заменить `Set<String>` на `LinkedHashSet<String>` (минимальное изменение):
```java
private Set<String> completedBlocks = new LinkedHashSet<>();
```

JPA `@ElementCollection` без `@OrderColumn` не гарантирует порядок при чтении — поэтому надёжнее для UI: brать список из `BlockOutput` отсортированный по `startedAt`. Но `LinkedHashSet` сохраняет порядок добавления в памяти во время одного run-а.

**Альтернативный подход (предпочтительный):** не трогать `completedBlocks`, а в API `/api/runs/{id}` добавить производное поле `events: List<{blockId, startedAt, completedAt, durationMs}>` сформированное из `blockOutputRepository.findByRunIdOrderByStartedAt(runId)`. UI берёт `events` вместо `completedBlocks` для рендера timeline.

### 4. `RunController.java` — отдать events в JSON

В DTO/проекции для `GET /api/runs/{id}` добавить:
```java
List<BlockOutput> outputs = blockOutputRepository.findByRunIdOrderByStartedAt(runId);
List<Map<String,Object>> events = outputs.stream().map(b -> Map.of(
    "blockId", b.getBlockId(),
    "startedAt", b.getStartedAt(),
    "completedAt", b.getCompletedAt(),
    "durationMs", b.getStartedAt() != null && b.getCompletedAt() != null
        ? java.time.Duration.between(b.getStartedAt(), b.getCompletedAt()).toMillis() : null
)).toList();
```

Добавить в `BlockOutputRepository`:
```java
List<BlockOutput> findByRunIdOrderByStartedAt(UUID runId);
```

### 5. `workflow-ui` — использовать events

Найти место в `RunPage.tsx` (или `BlockProgressTable.tsx`), где рендерится список блоков run-а. Сейчас оно скорее всего использует `run.completedBlocks` — заменить на `run.events`. Каждое событие показать как: `[blockId] [icon] длительность=Хм Yc` в хронологическом порядке.

Если поле `events` отсутствует в типе `PipelineRun` в `workflow-ui/src/types.ts` — добавить.

## Definition of Done

- [ ] `BlockOutput` имеет колонки `started_at`, `completed_at` (после рестарта workflow-core schema мигрирована)
- [ ] `PipelineRunner` проставляет timestamps при сохранении BlockOutput
- [ ] `BlockOutputRepository.findByRunIdOrderByStartedAt(...)` существует и работает
- [ ] `GET /api/runs/{id}` возвращает поле `events` (массив с blockId/startedAt/completedAt/durationMs) в порядке выполнения
- [ ] `workflow-ui/src/pages/RunPage.tsx` (или `BlockProgressTable.tsx`) рендерит timeline по `run.events`, показывает длительность каждого блока
- [ ] `gradle compileJava` зелёный
- [ ] `npm run build` зелёный
- [ ] Существующие тесты не сломаны (`gradle test --tests '!*IT'`)

## Зависимости
Нет.

## Что НЕ делать
- Не менять API `completedBlocks` (для backward-compat)
- Не добавлять отдельную таблицу `block_event` (избыточно, все данные есть в `block_output` после фикса)
- Не делать миграцию вручную через Flyway — `ddl-auto: update` справится с двумя новыми nullable-колонками

# Диагностика: "Задача в YouTrack" + падение run `e140b30f`

## Context

Пользователь запустил pipeline `skill-marketplace` с entry point `from_task_file`
(начало с блока `task_md`), но в UI появился статус "Задача в YouTrack" (блок
`youtrack_input`), и run упал. Оба симптома вызваны разными багами в текущем коде.

---

## Причина #1 — "Задача в YouTrack" появляется в UI

### Как воспроизводится

Entry point `from_task_file` инжектирует `youtrack_input` как `source: empty`.
`PipelineRunner.runFrom()` (старый код) **всегда** сохранял `BlockOutput` для каждого
pre-entry блока, в том числе с пустым map `{}`:

```java
// СТАРЫЙ код — сохранял "{}" даже для source: empty
String outputJson = objectMapper.writeValueAsString(injected); // → "{}"
BlockOutput blockOutput = BlockOutput.builder()
    .run(pipelineRun).blockId("youtrack_input").outputJson("{}").build();
pipelineRun.getOutputs().add(blockOutput);
```

Старый `RunPage.tsx` рендерил **все** блоки из `completedBlocks` без фильтрации:

```typescript
// СТАРЫЙ — показывал youtrack_input с output = {}
const completedStatuses = (data.completedBlocks ?? []).map(blockId => ({...}))
```

### Что уже исправлено (unstaged)

- **`PipelineRunner.java`** — добавлена проверка `if (!injected.isEmpty())` перед
  сохранением `BlockOutput`. Пустые инжекции не создают запись в БД.
- **`RunPage.tsx`** — добавлен фильтр `.filter(blockId => outputMap.has(blockId))`.
  Блоки без реального output исключаются из таблицы.

---

## Причина #2 — Run упал

### Цепочка сбоя

1. `RunController.resolveInputFields("task_file")` **не имел** `case "task_file"` →
   попадал в `default` → возвращал поле типа **Requirement (textarea)**.

2. UI отображал пользователю textarea "Requirement" вместо поля "Путь к файлу задачи".

3. Пользователь вводил путь к файлу в **Requirement** → frontend отправлял его как
   `body.requirement`, а не в `body.inputs`. В итоге `namedInputs = {}`.

4. `TaskMdInputBlock.run()` вызывал
   `stringInterpolator.interpolate("${input.task_file}", run, input)` →
   `input.task_file` отсутствовал → **`PathNotFoundException`** → run fails.

### Что уже исправлено (staged в `RunController.java`)

```java
case "task_file" -> List.of(
    Map.of("name", "task_file", "label", "Путь к файлу задачи (.md)", "type", "text",
           "placeholder", "/projects/my-project/tasks/FEAT-001-my-task.md", "required", true));
```

Теперь UI показывает правильное текстовое поле, и frontend отправляет
`inputs: {task_file: "..."}` → `namedInputs` корректно заполняется.

### Запасной механизм (unstaged `TaskMdInputBlock.java`)

Добавлен `catch (PathNotFoundException e)` с fallback на `input.get("requirement")` —
для обратной совместимости с ранее запущенными runs.

### Вторичная причина — файл задачи не существует

```bash
find /Users/home/Code/skill_marketplace -name "*.md" -path "*/tasks/*"
# → (пусто) — директории tasks/ нет в проекте
```

Даже после исправления UI, если пользователь укажет путь к несуществующему файлу,
`TaskMdInputBlock` выбросит `IllegalArgumentException: task_md_input: file not found`.

---

## Что нужно сделать

### Шаг 1 — Зафиксировать и задеплоить все исправления

Все изменения находятся в working tree (unstaged) / staging area:

| Файл | Статус | Что исправляет |
|---|---|---|
| `RunController.java` | staged (`M `) | Добавляет `case "task_file"` в UI |
| `PipelineRunner.java` | unstaged (` M`) | Не сохраняет `{}` BlockOutput |
| `RunPage.tsx` | unstaged (` M`) | Фильтрует блоки без output |
| `TaskMdInputBlock.java` | unstaged (` M`) | Fallback на `requirement` |
| `RunReportController.java` | untracked (`??`) | Новый endpoint отчётов |

Необходимо: commit → `docker-compose up --build`.

### Шаг 2 — Создать файл задачи

Создать `tasks/` директорию в skill_marketplace и первый task-файл:

**Путь в контейнере:** `/projects/skill_marketplace/tasks/active/<FEAT_ID>_<slug>.md`

**Путь на хосте (OrbStack):**
`/Users/home/Code/skill_marketplace/tasks/active/<FEAT_ID>_<slug>.md`

Формат файла (секции распознаются `TaskMdInputBlock`):

```markdown
# <Заголовок задачи>

## Как сейчас
<описание текущего поведения>

## Как надо
<описание целевого поведения>

## Вне scope
<что явно не делаем>

## Критерии приёмки
<верифицируемые условия готовности>
```

### Шаг 3 — Запустить run с правильным путём

В форме запуска (entry point "Задача из файла") указать:
```
/projects/skill_marketplace/tasks/active/<FEAT_ID>_<slug>.md
```
(контейнерный путь, т.к. backend работает внутри Docker).

---

## Verification

1. После деплоя: запустить новый run с `from_task_file` — `youtrack_input` не должен
   появляться в таблице блоков.
2. Блок `task_md` завершается успешно (status = complete, выходные поля: feat_id,
   slug, title, body).
3. `analysis` переходит в `awaiting_approval` — пользователь одобряет и pipeline
   продолжается.

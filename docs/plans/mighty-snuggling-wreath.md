# Полный рефакторинг абстракции трекера задач

## Context

Интерфейс `TaskTracker` и реестр `TaskTrackerRegistry` уже существуют и правильно спроектированы.
Проблема в том, что три legacy-блока обходят абстракцию и создают `YouTrackClient` напрямую:

- `YouTrackInputBlock` (`youtrack_input`) — прямой `new YouTrackClient(...)`
- `YouTrackTasksInputBlock` (`youtrack_tasks_input`) — прямой `new YouTrackClient(...)`
- `YouTrackTaskCreationBlock` (`youtrack_tasks`) — прямой `new YouTrackClient(...)`

Новый `TaskInputBlock` (`task_input`) уже использует `TaskTrackerRegistry` корректно.
Также у `YouTrackAdapter` есть незакрытые TODO: `updateStatus` бросает UnsupportedOperationException,
`createSubtasks` не линкует созданные задачи как подзадачи. `JiraAdapter` и `LinearAdapter` — стабы.

**Цель:** все блоки работы с задачами работают через `TaskTracker`-абстракцию, добавлены два новых
универсальных блока, `YouTrackAdapter` полный, начальная реализация `JiraAdapter`.

---

## Фазы реализации

### Фаза A — Дополнить `YouTrackClient` и `YouTrackAdapter`

**Файл:** `workflow-core/src/main/java/com/workflow/integrations/YouTrackClient.java`

Добавить два новых метода:

1. **`updateState(issueId, stateName)`** — PATCH `/api/issues/{issueId}?fields=id` с телом:
   ```json
   { "customFields": [{ "$type": "StateIssueCustomField", "name": "State", "value": { "name": "<stateName>" } }] }
   ```
   Заголовок: `Content-Type: application/json`, Bearer-токен.

2. **`linkSubtask(parentId, childId)`** — POST `/api/issues/{childId}/issueLinks?fields=id` с телом:
   ```json
   { "$type": "IssueLink", "direction": "INWARD", "linkType": { "name": "Subtask" }, "issues": [{ "idReadable": "<parentId>" }] }
   ```

**Файл:** `workflow-core/src/main/java/com/workflow/integrations/tracker/YouTrackAdapter.java`

- `updateStatus()` — вызывает `client.updateState(issueId, status)` вместо `throw UnsupportedOperationException`
- `createSubtasks()` — после `client.createIssue()` вызывает `client.linkSubtask(parentIssueId, createdId)` в try-catch с warn-логом

---

### Фаза B — Рефакторинг legacy-блоков на `TaskTrackerRegistry`

Все три блока инжектируют `@Autowired TaskTrackerRegistry trackerRegistry` и убирают прямой `new YouTrackClient(...)`.
Провайдер резолвится как `provider = stringOr(cfg.get("provider"), "youtrack")` для обратной совместимости.
Конфиг адаптера берётся из `cfg.get("_" + provider + "_config")` — та же схема что в `TaskInputBlock`.

#### `YouTrackInputBlock.java`
- Убрать `import com.workflow.integrations.YouTrackClient`
- Добавить `@Autowired TaskTrackerRegistry trackerRegistry`
- Вместо `YouTrackClient client = new YouTrackClient(...); client.getIssue(issueId)` →
  `trackerRegistry.get(provider).fetchIssue(issueId, trackerConfig)`
- Из `TaskIssue` достать `summary`, `description`, `readableId`, `url`
- Вывод остаётся тем же: `requirement`, `task_mode`, `youtrack_source_issue` (для обратной совместимости)
  плюс добавить `issue` (полный `TaskIssue` как map) и `provider`

#### `YouTrackTasksInputBlock.java`
- Убрать `import com.workflow.integrations.YouTrackClient`
- Добавить `@Autowired TaskTrackerRegistry trackerRegistry`
- Вместо `client.getSubtasks(parentIssueId)` → `trackerRegistry.get(provider).listSubtasks(parentIssueId, trackerConfig)`
- Из `List<TaskIssue>` собрать `tasks` и `youtrack_issues` (ключ сохранить для обратной совместимости)

#### `YouTrackTaskCreationBlock.java`
- Убрать `import com.workflow.integrations.YouTrackClient`
- Добавить `@Autowired TaskTrackerRegistry trackerRegistry`
- В режиме `decompose`: вместо цикла `ytClient.createIssue(...)` →
  построить `List<SubtaskSpec>` из LLM-ответа и вызвать `trackerRegistry.get(provider).createSubtasks(sourceIssueId, specs, trackerConfig)`
- В режиме `supplement`: `YouTrackClient.updateIssue()` нет в интерфейсе `TaskTracker` —
  добавить метод `void updateIssue(String issueId, String summary, String description, Map<String, Object> config)` в интерфейс `TaskTracker` и реализовать в `YouTrackAdapter` (делегирует `client.updateIssue()`)
- Добавление комментария к source issue через `trackerRegistry.get(provider).addComment()`
- Вывод: `tasks`, `youtrack_issues` (compat) + `issues` (tracker-agnostic alias, тот же контент)

---

### Фаза C — Два новых универсальных блока

#### `TaskSubtasksInputBlock.java` (блок: `task_subtasks_input`)

Provider-agnostic замена `youtrack_tasks_input`. Конфиг:
```yaml
- id: list_subtasks
  block: task_subtasks_input
  config:
    provider: youtrack    # или jira, linear
    parent_issue_id: PROJ-42    # опционально, fallback из input.task_input.issue.readableId
```

Вывод:
```json
{
  "subtasks": [{ "id": "PROJ-43", "summary": "...", "status": "...", "url": "..." }],
  "issues": [...]   // alias для subtasks
}
```

Логика: `trackerRegistry.get(provider).listSubtasks(parentId, trackerConfig)`

#### `TaskCreationBlock.java` (блок: `task_creation`)

Provider-agnostic замена `youtrack_tasks`. Содержит весь LLM-промпт (decompose/supplement) из `YouTrackTaskCreationBlock`, но:
- Вместо `ytClient.createIssue()` → `trackerRegistry.get(provider).createSubtasks()`
- Вместо `ytClient.updateIssue()` → `trackerRegistry.get(provider).updateIssue()`
- Вместо `ytClient.addComment()` → `trackerRegistry.get(provider).addComment()`

Конфиг:
```yaml
- id: create_tasks
  block: task_creation
  config:
    provider: youtrack    # или jira, linear
    mode: decompose       # или supplement (опционально, default decompose)
```

Вывод:
```json
{
  "tasks": [...],
  "issues": [{ "id": "PROJ-42", "url": "...", "summary": "..." }],
  "youtrack_issues": [...]  // backward-compat alias = issues
}
```

---

### Фаза D — Базовая реализация `JiraAdapter`

**Файл:** `workflow-core/src/main/java/com/workflow/integrations/tracker/JiraAdapter.java`

Реализовать через `java.net.http.HttpClient` (без нового JiraClient, встроить HTTP прямо в адаптер — Jira API проще):

Config keys: `baseUrl`, `token` (Bearer или `email:apiToken` Basic), `project`.

Jira REST v3 endpoints:
- `fetchIssue` → GET `/rest/api/3/issue/{issueIdOrKey}?fields=summary,description,status,subtasks`
- `listSubtasks` → берётся из поля `fields.subtasks` основной задачи (дополнительный GET для каждой если нужно description)
- `createSubtasks` → POST `/rest/api/3/issue` с телом `{ fields: { project: {key}, summary, description, issuetype: {name: "Subtask"}, parent: {key: parentId} } }`
- `addComment` → POST `/rest/api/3/issue/{issueId}/comment` с `{ body: { type: "doc", version: 1, content: [...] } }`
- `updateStatus` → POST `/rest/api/3/issue/{issueId}/transitions` (требует сначала GET transitions — реализовать в рамках этой фазы)

Auth: `Authorization: Bearer <token>` для Jira Cloud token; для Server — Basic base64(`email:token`).

---

### Фаза E — Расширение интерфейса `TaskTracker`

Добавить в `TaskTracker.java`:
```java
/** Update summary and description of an existing issue. */
void updateIssue(String issueId, String summary, String description,
                 Map<String, Object> config) throws Exception;
```

Реализовать в:
- `YouTrackAdapter` — делегировать `client.updateIssue(issueId, summary, description)`
- `JiraAdapter` — PUT `/rest/api/3/issue/{issueId}` с `{ fields: { summary, description: {...ADF...} } }`
- `LinearAdapter` — `throw UnsupportedOperationException` (стаб)

---

## Файлы для изменения

| Файл | Тип изменения |
|------|---------------|
| `integrations/YouTrackClient.java` | +2 метода: `updateState`, `linkSubtask` |
| `integrations/tracker/TaskTracker.java` | +1 метод: `updateIssue` |
| `integrations/tracker/YouTrackAdapter.java` | реализовать `updateStatus`, fix `createSubtasks`, добавить `updateIssue` |
| `integrations/tracker/JiraAdapter.java` | полная реализация (заменить стаб) |
| `integrations/tracker/LinearAdapter.java` | добавить `updateIssue` стаб |
| `blocks/YouTrackInputBlock.java` | убрать прямой YouTrackClient, использовать TaskTrackerRegistry |
| `blocks/YouTrackTasksInputBlock.java` | убрать прямой YouTrackClient, использовать TaskTrackerRegistry |
| `blocks/YouTrackTaskCreationBlock.java` | убрать прямой YouTrackClient, использовать TaskTrackerRegistry |
| `blocks/TaskSubtasksInputBlock.java` | **новый файл** |
| `blocks/TaskCreationBlock.java` | **новый файл** |

---

## Порядок исполнения

1. Фаза E — расширить интерфейс `TaskTracker` (иначе не скомпилируется)
2. Фаза A — дополнить `YouTrackClient` + `YouTrackAdapter`
3. Фаза D — реализовать `JiraAdapter`
4. Фаза B — рефакторинг legacy-блоков
5. Фаза C — создать два новых блока

---

## Верификация

1. **Компиляция:** `cd workflow-core && gradle compileJava` — должна пройти без ошибок
2. **Unit-тесты:** `gradle test --tests 'com.workflow.integrations.tracker.*'` — `TaskTrackerRegistryTest` должен пройти
3. **Ручная проверка с YouTrack:** запустить `gradle bootRun`, POST `/api/runs` с конфигом использующим `youtrack_input` — должно загрузить задачу без ошибок
4. **Новые блоки:** добавить в тестовый YAML пайплайн `task_creation` с `provider: youtrack`, прогнать через `/api/runs`
5. **Обратная совместимость:** существующий `config/feature.yaml` (использует `youtrack_input`, `youtrack_tasks`) должен работать без изменений в YAML

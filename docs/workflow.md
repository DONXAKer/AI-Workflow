# Workflow — Документация

**Workflow** — платформа автоматизации разработки фич на основе Claude AI. Принимает требование (текст или задача в YouTrack), прогоняет через цепочку настраиваемых блоков и на выходе даёт задачи в YouTrack, рабочий код в GitLab MR или GitHub PR, и результат CI — всё под контролем человека через approval gates.

**Стек:** Java 21 + Spring Boot 3.4 (backend) · React 18 + TypeScript (frontend) · H2 (persistence) · WebSocket/STOMP (live updates)

---

## Содержание

1. [Запуск](#запуск)
2. [Как это работает](#как-это-работает)
3. [Конфигурация pipeline](#конфигурация-pipeline)
4. [Блоки](#блоки)
5. [Верификация и loopback](#верификация-и-loopback)
6. [Условные блоки](#условные-блоки)
7. [Entry Points](#entry-points)
8. [Approval Gates](#approval-gates)
9. [REST API](#rest-api)
10. [Переменные окружения](#переменные-окружения)

---

## Запуск

### Backend (Java)

```bash
cd /path/to/WorkflowJava/workflow-core
gradle bootRun          # порт 8080
gradle build            # сборка JAR
gradle test
```

Настройки в `src/main/resources/application.yaml`:

```yaml
workflow:
  mode: cli             # cli | gui  (тип approval gate)
  config-dir: ./config  # папка с pipeline YAML
```

### Frontend (React)

```bash
cd /path/to/WorkflowJava/workflow-ui
npm install
npm run dev             # порт 5173, проксирует /api и /ws на :8080
npm run build           # production сборка → dist/
```

### Интеграции

Настраиваются через UI (страница Integrations) или через `POST /api/integrations`. Поддерживаются: **YouTrack**, **GitLab**, **GitHub**, **OpenRouter** (LLM).

---

## Как это работает

### Полный цикл

```
Требование (текст / YouTrack issue)
    │
    ▼
youtrack_input ──► analysis ──► verify? ──► clarification? ──► youtrack_task_creation
                                                                        │
                                                                        ▼
                                                               code_generation ──► verify?
                                                                        │
                                                                        ▼
                                                                    gitlab_mr
                                                                        │
                                                                        ▼
                                                                    gitlab_ci ──► on_failure loopback?
```

### Ключевые принципы

- **Блоки** — атомарные шаги pipeline. Каждый реализует `Block.run(input, config, run) → Map`.
- **Зависимости** — блоки связаны через `depends_on`. Порядок выполнения — топологическая сортировка.
- **State** — каждый run сохраняется в H2 (`PipelineRun` + `BlockOutput`). Можно прервать и продолжить через `POST /api/runs/{id}/resume`.
- **Approval gates** — блок с `approval: true` останавливается и ждёт решения через WebSocket (GUI) или терминал (CLI).
- **Loopback** — при провале верификации или CI pipeline автоматически возвращается к нужному блоку с контекстом о проблемах.
- **Condition** — блок пропускается если условие не выполнено (например, пропустить clarification для простых задач).

---

## Конфигурация pipeline

### Структура файла

```yaml
name: my-pipeline
description: "Описание pipeline"

integrations:
  youtrack: youtrack-default    # имя интеграции из БД
  gitlab:   gitlab-default
  github:   github-default

knowledge_base:
  sources:
    - type: git
      url: "git@gitlab.com:team/repo.git"
      branch: main
    - type: files
      path: ./docs

entry_points:
  - id: from_scratch
    name: "Новое требование"
    from_block: youtrack_input
    requires_input: requirement   # requirement | youtrack_issue | none

  - id: tasks_exist
    name: "Задачи уже в YouTrack"
    from_block: codegen
    requires_input: youtrack_issue
    auto_detect: youtrack_subtasks
    inject:
      - { block_id: analysis,      source: empty }
      - { block_id: clarification, source: empty }
      - { block_id: tasks,         source: youtrack }

pipeline:
  - id: <block-id>
    block: <block-type>
    depends_on: [<block-id>, ...]
    approval: true
    condition: "$.analysis.estimated_complexity != 'low'"   # опционально
    agent:
      model: claude-sonnet-4-6
      maxTokens: 8192
      temperature: 1.0
    verify:          # опционально — см. раздел Верификация
      ...
    on_failure:      # опционально — для CI блоков
      ...
    config:
      key: value
```

### Поля блока

| Поле | Тип | Описание |
|---|---|---|
| `id` | string | Уникальный идентификатор блока |
| `block` | string | Тип блока (см. [Блоки](#блоки)) |
| `depends_on` | list | ID блоков, чьи outputs передаются в input |
| `approval` | bool | Останавливаться для подтверждения (default: `true`) |
| `condition` | string | Выражение для пропуска блока (см. [Условные блоки](#условные-блоки)) |
| `agent.model` | string | Модель Claude (default: `claude-sonnet-4-6`) |
| `agent.maxTokens` | int | Максимум токенов (default: `8192`) |
| `agent.temperature` | float | Температура (default: `1.0`) |
| `verify` | object | Конфиг верификации output (см. [Верификация](#верификация-и-loopback)) |
| `on_failure` | object | Loopback при ошибке CI (см. [Верификация](#верификация-и-loopback)) |
| `config` | dict | Произвольные параметры конкретного блока |

---

## Блоки

### `youtrack_input`

Читает задачу из YouTrack, формирует `requirement` для pipeline.

```yaml
- id: youtrack_input
  block: youtrack_input
  approval: false
  config:
    issue_id: ""    # если пусто — пробрасывает requirement без изменений
```

**Output:** `requirement`, `youtrack_source_issue` (id, url, summary)

---

### `analysis`

Анализирует требование через Claude. Запрашивает Knowledge Base для контекста. При retry получает feedback из предыдущего verify.

```yaml
- id: analysis
  block: analysis
  depends_on: [youtrack_input]
  agent:
    model: claude-opus-4-6
  approval: true
```

**Output:** `summary`, `affected_components`, `technical_approach`, `estimated_complexity` (low/medium/high), `risks`, `open_questions`

---

### `clarification`

Задаёт уточняющие вопросы через Claude. Обычно используется с `condition`, чтобы пропускать при простых задачах.

```yaml
- id: clarification
  block: clarification
  depends_on: [analysis]
  condition: "$.analysis.estimated_complexity != 'low'"
  approval: true
```

**Output:** `approved_approach`, `answers`

---

### `youtrack_task_creation`

Создаёт подзадачи в YouTrack на основе анализа.

```yaml
- id: tasks
  block: youtrack_task_creation
  depends_on: [analysis, clarification]
  approval: true
```

**Output:** `tasks` (list), `youtrack_issues` (list с id/url)

---

### `youtrack_tasks_input`

Читает существующие подзадачи из YouTrack (для entry point `tasks_exist`).

```yaml
- id: tasks
  block: youtrack_tasks_input
  approval: false
```

**Output:** `tasks`, `youtrack_issues`

---

### `code_generation`

Генерирует изменения кода для каждой задачи. Запрашивает Knowledge Base. При retry получает feedback из verify или CI.

```yaml
- id: codegen
  block: code_generation
  depends_on: [tasks, analysis]
  agent:
    model: claude-opus-4-6
  approval: true
```

**Output:** `branch_name`, `changes` (list файлов), `test_changes`, `commit_message`, `tasks_generated`

---

### `verify`

Структурная и LLM-проверка качества output предыдущего блока. При провале не выбрасывает ошибку — возвращает `passed: false`, что запускает loopback.

```yaml
- id: verify_analysis
  block: verify
  depends_on: [analysis]
  approval: false
  verify:
    subject: analysis           # id блока чей output проверяем
    checks:
      - field: affected_components
        rule: min_items
        value: 1
        message: "Укажите хотя бы один компонент"
      - field: technical_approach
        rule: min_length
        value: 100
      - field: estimated_complexity
        rule: one_of
        value: [low, medium, high]
    llm_check:
      enabled: true
      prompt: |
        Оцени анализ 0-10. Достаточно ли деталей для реализации?
        JSON: {"passed": bool, "score": 0-10, "issues": [], "recommendation": ""}
      min_score: 7
    on_fail:
      action: loopback
      target: analysis          # блок к которому возвращаемся
      max_iterations: 2
      inject_context:
        feedback: "$.verify_analysis.issues"
```

**Правила проверки (`rule`):**

| Правило | Описание |
|---------|----------|
| `not_empty` | Значение не null и не пустое |
| `min_length` | Длина строки ≥ value |
| `max_length` | Длина строки ≤ value |
| `min_items` | Количество элементов списка ≥ value |
| `max_items` | Количество элементов списка ≤ value |
| `one_of` | Значение входит в список value |
| `regex` | Строка соответствует регулярному выражению |
| `gt` | Число строго больше value |
| `lt` | Число строго меньше value |

**Output:** `passed`, `score`, `checks_passed`, `checks_failed`, `issues`, `subject_block`, `iteration`, `recommendation`

---

### `git_branch_input`

Читает diff существующей ветки (для entry point `branch_exists`).

```yaml
- id: codegen
  block: git_branch_input
  config:
    branch: feature/PROJ-42-login
```

**Output:** `branch`, `diff`, `changed_files`

---

### `mr_input`

Читает существующий открытый MR/PR.

```yaml
- id: mr
  block: mr_input
  approval: false
```

**Output:** `mr_id`, `branch`, `url`, `title`

---

### `gitlab_mr`

Создаёт merge request в GitLab.

```yaml
- id: mr
  block: gitlab_mr
  depends_on: [codegen]
  approval: true
  config:
    target_branch: main
```

**Output:** `mr_id`, `mr_url`, `branch`, `title`

---

### `gitlab_ci`

Мониторит CI pipeline GitLab, ждёт завершения. Поддерживает `on_failure: loopback` для возврата к code_generation при падении CI.

```yaml
- id: deploy
  block: gitlab_ci
  depends_on: [mr]
  approval: false
  config:
    timeout: 3600
  on_failure:
    action: loopback
    target: codegen
    max_iterations: 2
    inject_context:
      ci_stages: "$.deploy.stages"
    failed_statuses: [failure, failed, timeout]
```

**Output:** `pipeline_id`, `pipeline_url`, `status`, `stages`

---

### `github_pr`

Создаёт Pull Request в GitHub.

```yaml
- id: pr
  block: github_pr
  depends_on: [codegen]
  approval: true
  config:
    base_branch: main
```

**Output:** `pr_number`, `pr_url`, `branch`, `title`

---

### `github_actions`

Мониторит GitHub Actions workflow. Поддерживает `on_failure: loopback`.

```yaml
- id: ci
  block: github_actions
  depends_on: [pr]
  approval: false
  config:
    timeout: 3600
  on_failure:
    action: loopback
    target: codegen
    max_iterations: 2
```

**Output:** `run_id`, `run_url`, `status`, `jobs`

---

## Верификация и loopback

Loopback — runtime-механизм: при провале верификации или CI pipeline сбрасывает `completed_blocks` для диапазона блоков и повторяет выполнение с контекстом о проблемах. DAG не меняется.

### Verify loopback

```
analysis ──► verify_analysis ──✗──► loopback ──► analysis (+ feedback)
                              ↑
                          passed=false
                          issues=[...]
```

Блок `analysis` при retry получает `_loopback` в input с полями `issues`, `recommendation`, `iteration`. Claude автоматически видит эти данные в промпте.

### CI loopback

```
codegen ──► mr ──► deploy ──✗──► loopback ──► codegen (+ ci_stages)
                          ↑
                      status=failure
```

Для CI блоков (`gitlab_ci`, `github_actions`) используется `on_failure` вместо `verify`.

### Счётчик итераций

Хранится в `PipelineRun.loopIterations` (Map с ключом `loopback:{verify_id}:{target_id}`). При превышении `max_iterations` pipeline падает с ошибкой.

### inject_context

Позволяет передать значения из state в loopback-контекст:

```yaml
inject_context:
  feedback: "$.verify_analysis.issues"   # $.block_id.field
  approach: "$.analysis.technical_approach"
```

---

## Условные блоки

Блок пропускается если `condition` возвращает `false`. Output помечается `{_skipped: true}`.

### Синтаксис

```
$.block_id.field [| length] OPERATOR value
```

**Операторы:** `==`, `!=`, `>`, `<`, `>=`, `<=`

**Примеры:**

```yaml
# Пропустить clarification если задача простая
condition: "$.analysis.estimated_complexity != 'low'"

# Пропустить если список пустой
condition: "$.tasks.tasks | length > 0"

# Сравнение строк
condition: "$.youtrack_input.status == 'In Progress'"
```

---

## Entry Points

Named entry points позволяют начать pipeline с произвольного блока и автоматически инжектировать данные для пропущенных блоков.

### Конфигурация

```yaml
entry_points:
  - id: from_scratch
    name: "Новое требование"
    from_block: youtrack_input
    requires_input: requirement       # requirement | youtrack_issue | none
    description: "Полный цикл с нуля"

  - id: tasks_exist
    name: "Задачи уже в YouTrack"
    from_block: codegen
    requires_input: youtrack_issue
    auto_detect: youtrack_subtasks    # для автодетекта
    inject:
      - block_id: analysis
        source: empty                 # пустой output
      - block_id: clarification
        source: empty
      - block_id: tasks
        source: youtrack              # из YouTrack

  - id: branch_exists
    name: "Ветка уже создана"
    from_block: mr
    auto_detect: gitlab_branch
    inject:
      - block_id: codegen
        source: gitlab_branch

  - id: mr_open
    name: "MR уже открыт"
    from_block: deploy
    auto_detect: gitlab_mr
    inject:
      - block_id: mr
        source: gitlab_mr
```

### Использование через API

```bash
POST /api/runs
{
  "configPath": "./config/pipeline.yaml",
  "requirement": "PROJ-42",
  "entryPointId": "tasks_exist"
}
```

### Получение списка entry points

```bash
GET /api/pipelines/entry-points?configPath=./config/pipeline.yaml
```

---

## Approval Gates

При `approval: true` pipeline останавливается и ждёт решения человека.

### GUI режим (`workflow.mode: gui`)

Решение принимается через WebSocket:

```bash
POST /api/runs/{runId}/approval
{
  "blockId": "analysis",
  "decision": "APPROVE",        # APPROVE | EDIT | REJECT | SKIP | JUMP
  "output": {...},              # изменённый output при EDIT
  "skipFuture": false,          # автоапрув всех следующих блоков
  "targetBlockId": "codegen"    # при JUMP
}
```

### CLI режим (`workflow.mode: cli`)

Интерактивный промпт в терминале: `[A]pprove / [E]dit / [R]eject / [S]kip-future / [J]ump`

---

## REST API

| Метод | Путь | Описание |
|-------|------|----------|
| `POST` | `/api/runs` | Запустить pipeline |
| `GET` | `/api/runs` | Список runs (пагинация, фильтры) |
| `GET` | `/api/runs/stats` | Счётчики: active, awaiting, completed, failed |
| `GET` | `/api/runs/{id}` | Детали run |
| `POST` | `/api/runs/{id}/approval` | Решение по approval gate |
| `POST` | `/api/runs/{id}/cancel` | Отменить run |
| `GET` | `/api/pipelines` | Список pipeline конфигов |
| `GET` | `/api/pipelines/entry-points?configPath=` | Entry points пайплайна |
| `GET` | `/api/integrations` | Список интеграций |
| `POST` | `/api/integrations` | Добавить интеграцию |
| `PUT` | `/api/integrations/{id}` | Обновить интеграцию |
| `DELETE` | `/api/integrations/{id}` | Удалить интеграцию |
| `WS` | `/ws` | WebSocket (STOMP) — live события |

### Запуск run

```bash
POST /api/runs
{
  "configPath": "./config/pipeline.yaml",
  "requirement": "Добавить авторизацию через Google",
  "fromBlock": "codegen",              # опционально
  "entryPointId": "tasks_exist",       # опционально (вместо fromBlock)
  "injectedOutputs": {                 # опционально
    "analysis": {...}
  },
  "runId": "uuid"                      # опционально, генерируется автоматически
}
```

### WebSocket события (STOMP)

| Topic | Событие |
|-------|---------|
| `/topic/runs` | run started/completed |
| `/topic/runs/{runId}` | block started/completed, approval request |

### Фильтры GET /api/runs

```
?status=RUNNING,FAILED&pipelineName=my-pipeline&search=login&from=2026-01-01&to=2026-12-31&page=0&size=25
```

---

## Переменные окружения

Устанавливаются как переменные окружения JVM или в `application.yaml`.

| Переменная | Описание |
|-----------|----------|
| `OPENROUTER_API_KEY` | API ключ OpenRouter (LLM) |
| `ANTHROPIC_API_KEY` | API ключ Anthropic (альтернатива) |

Реквизиты интеграций (YouTrack/GitLab/GitHub) хранятся в БД и настраиваются через UI или API — переменные окружения для них не нужны.

### application.yaml

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./workflow-db    # путь к БД
  jpa:
    hibernate:
      ddl-auto: update                 # auto-migrate схемы

workflow:
  mode: cli                            # cli | gui
  config-dir: ./config                 # папка с YAML конфигами
  state-dir: ./.workflow/state         # не используется в Java (есть H2)

server:
  port: 8080
```

---

## Добавление нового блока

1. Создать `src/main/java/com/workflow/blocks/MyBlock.java`:

```java
@Component
public class MyBlock implements Block {

    @Override
    public String getName() { return "my_block"; }

    @Override
    public String getDescription() { return "Описание блока"; }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run)
            throws Exception {
        String requirement = (String) input.getOrDefault("requirement", "");
        // ... логика ...
        Map<String, Object> result = new HashMap<>();
        result.put("my_field", "value");
        return result;
    }
}
```

2. Блок автоматически регистрируется через `@Component` — вручную ничего не нужно.

3. Использовать в YAML:

```yaml
- id: my_step
  block: my_block
  depends_on: [analysis]
  approval: true
```

---

## Структура проекта

```
WorkflowJava/
├── workflow-core/          # Java Spring Boot backend
│   ├── src/main/java/com/workflow/
│   │   ├── api/            # REST controllers, WebSocket handler
│   │   ├── blocks/         # 13 блоков (Block interface + реализации)
│   │   ├── config/         # POJO для YAML десериализации
│   │   ├── core/           # PipelineRunner, PipelineRun (JPA), ApprovalGate
│   │   ├── integrations/   # YouTrack, GitLab, GitHub клиенты
│   │   ├── knowledge/      # KnowledgeBase (RAG)
│   │   └── llm/            # LlmClient (OpenRouter / Anthropic)
│   └── config/
│       └── pipeline.example.yaml
└── workflow-ui/            # React + TypeScript frontend
    └── src/
        ├── components/     # UI компоненты
        ├── context/        # RunsContext (global state)
        ├── hooks/          # useWebSocket, useRuns, ...
        └── pages/          # Dashboard, Pipelines, RunHistory, ...

Workflow/                   # Shared configs and docs
├── config/
│   └── pipeline.example.yaml
└── docs/
    └── workflow.md         # этот файл
```

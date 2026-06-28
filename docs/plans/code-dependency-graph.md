# План: граф зависимостей кода (file-level) для пайплайна — PoC

## Context

Сейчас индексация кода — чисто **векторный RAG** (Ollama `nomic-embed-text` → Qdrant,
коллекция `code_<slug>`, line-chunking, top-3 по косинусу; `Search`-tool + `auto_inject_rag`).
Графа зависимостей нет. Гипотеза пользователя: пайплайну полезно «**сначала искать зависимости**»
файла — что он тянет и кто зависит от него — до правок. Вектор-поиск этого не даёт (находит
семантически похожее, а не структурно связанное).

Делаем **тонкий сквозной PoC**: детерминированный **файловый import-граф**, доступный агенту через
новый нативный tool `Deps`, на тех же хуках индексации. Цель — измерить, реально ли это помогает
агенту в пайплайне, прежде чем вкладываться в символьный слой и RAG-гибрид.

**Решения (grill-сессия, 8 развилок — все приняты):**
1. Гранулярность — **файл→файл** (import-рёбра); символьный слой (функции/вызовы) — позже.
2. Потребление — **нативный tool `Deps`** (агент сам зовёт), не пассивный RAG-инжект.
3. Направление — **оба** (`out` = что импортирует X, `in` = кто импортирует X), параметр `direction`.
4. Глубина — **depth=1 по умолчанию**, опц. транзитив до 2–3 с капом узлов (BFS в Java).
5. Языки v1 — **Java, TypeScript/JS, Python, Go** (только внутренние рёбра, external пропускаем).
6. Ответ tool'а — **лёгкие рёбра** `{path, direction, import_type, line}`; контент агент берёт `Read`.
7. Хранилище — **Postgres-таблица `project_index_edge` + Liquibase**, на хуках reindex; без Neo4j.
8. Объём — **PoC-срез + метрика**; RAG-авторасширение и символы — НЕ сейчас.

## Что переиспользуем

- **Tool-паттерн:** `tools/SearchTool.java` (ProjectContext.get → slug → KnowledgeBase) — зеркалим в `DepsTool`.
- **Регистрация tool:** как 7 существующих нативных (ToolRegistry) — `DepsTool` 8-м, read-only.
- **Обход файлов + regex:** `project/ProjectStackScanner.java` (`Files.walk`, FILE_PATTERNS) — паттерн для `ImportGraphExtractor`.
- **Индекс-инфра:** `knowledge/ProjectIndexEntry.java` + `ProjectIndexEntryRepository` — образец для `ProjectIndexEdge` (+ Liquibase, как `db/changelog/`).
- **Хуки построения:** `knowledge/ProjectIndexService.java` (reindexFull / `reindexDeltaAsync:145`) и `core/PipelineRunner.java:1718` (delta после прогона).

## Backend (`workflow-core`)

1. **Хранилище рёбер.** `knowledge/ProjectIndexEdge.java` (JPA entity) + `ProjectIndexEdgeRepository`:
   - Таблица `project_index_edge`: `{id, project_slug, from_path, to_path, import_type, from_line, indexed_at}`.
   - Индексы: `(project_slug, from_path)`, `(project_slug, to_path)`; unique `(project_slug, from_path, to_path, from_line)`.
   - Репозиторий: `findByProjectSlugAndFromPath`, `findByProjectSlugAndToPath`, `deleteByProjectSlugAndFromPath`.
   - **Liquibase changeset** `db/changelog/changelog-NNN-import-graph.yaml` (новые схемы — через Liquibase, не только ddl-auto; см. CLAUDE.md).

2. **Извлечение рёбер.** `knowledge/ImportGraphExtractor.java`:
   - На файл: по расширению выбрать language-стратегию, regex вытащить import-стейтменты, **резолвить в путь файла репо**, external/неразрешённые — отбросить (считать счётчик).
   - Резолв per-language: Java `import a.b.C;`→`**/a/b/C.java` (+ same-package); TS/JS относительные `./`,`../` (+ `.ts/.tsx/.js/.jsx/index`); Python `from a.b import c`/`import a.b`→модуль-в-файл; Go `import "<mod>/pkg"` через `go.mod` module-prefix → директория пакета (все `.go` в ней).
   - Возвращает `List<Edge(fromPath, toPath, importType, fromLine)>` для файла.

3. **Построение/обновление.** В `ProjectIndexService` (или новый `ImportGraphService`):
   - `reindexGraphFull(slug, workingDir)` — walk репо, extractor по файлу, заменить рёбра проекта.
   - `updateGraphDelta(slug, changed[])` — для изменённых: удалить их `from`-рёбра, перестроить.
   - **Триггеры:** вызвать `reindexGraphFull` внутри существующего `POST /api/projects/{slug}/reindex`
     (одна кнопка строит вектор+граф) и `updateGraphDelta` на `PipelineRunner.java:1718` рядом с `reindexDeltaAsync`.
   - Graceful: если Postgres/таблицы нет — no-op (как knowledge при отсутствии Qdrant).

4. **Tool `Deps`.** `tools/DepsTool.java` (зеркало `SearchTool`):
   - Вход: `path` (required), `direction: out|in|both` (default `both`), `depth` (default 1, cap 3), `max_nodes` (cap ~50).
   - `ProjectContext.get()` → slug → запросы к `ProjectIndexEdgeRepository`; транзитив — BFS в Java с капом.
   - Выход: лёгкий список `{path, direction, import_type, line}` (без контента).
   - Зарегистрировать в ToolRegistry; **read-only** — доступен рядом с `Read/Grep/Glob` (тот же allow-уровень, что у `Search`).

## Frontend

Опционально (можно отложить): на странице проекта показать счётчик рёбер графа рядом с index-stats.
В PoC — не обязательно; проверка идёт через tool в прогоне.

## Вне scope (зафиксировано)

- Символьный граф (функции/вызовы/наследование, AST/tree-sitter) — следующий слой.
- RAG-авторасширение (граф-соседи к векторным хитам в `prependRagHits`/`buildRagSection`) — шаг 2.
- Neo4j / визуализация графа.

## Verification (end-to-end)

1. **Unit:** `ImportGraphExtractorTest` — на мини-фикстурах каждого языка (Java package-import, TS относительный,
   Python module, Go package) проверить корректные рёбра и отбрасывание external/неразрешённых.
   `DepsToolTest` — direction out/in/both, depth=1 vs транзитив с капом, пустой результат для файла без рёбер.
   Гонять под **JDK 21** (память `project_test_jdk`).
2. **Build:** `./gradlew test`; Liquibase-changeset применяется на старте (Postgres `localhost:5432`,
   `WORKFLOW_MODE=gui` — память `project_local_backend_run`).
3. **Ручной:** `POST /api/projects/default/reindex` → проверить заполнение `project_index_edge`
   (`docker exec postgres16 psql -U warcard -d workflow_ai -c "select count(*),import_type from project_index_edge group by import_type"`).
   Дёрнуть `Deps` через прогон `agent_with_tools` на самой AI-Workflow (Java+TS): убедиться, что
   для `PipelineRunner.java` возвращаются реальные `out`-зависимости и `in`-зависимые.
4. **Метрика успеха (главное):** на 1–2 реальных задачах в AI-Workflow агент через `Deps` находит
   истинную зависимость/зависимого, которую вектор-поиск пропустил, **или** сокращает правки «не того»
   файла. Если нет — закрываем как изученный тупик (граф остаётся, но не разворачиваем дальше).

## Критические файлы

Новые: `knowledge/ProjectIndexEdge.java`, `knowledge/ProjectIndexEdgeRepository.java`,
`knowledge/ImportGraphExtractor.java`, `tools/DepsTool.java`,
`db/changelog/changelog-NNN-import-graph.yaml`, тесты `*Test`.
Правки: `knowledge/ProjectIndexService.java` (или новый `ImportGraphService`),
`api/ProjectController.java` (reindex → +граф), `core/PipelineRunner.java:1718` (delta-хук),
ToolRegistry (регистрация `DepsTool`), `db.changelog-master.yaml` (include changeset).

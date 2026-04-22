# Release Checklist — AI-Workflow

Шаблон для подготовки и проведения выката. Заполняется перед каждым релизом.

---

## Метаданные релиза

| Поле | Значение |
|---|---|
| Версия/тег | `v?.?.?` |
| Дата выката | |
| Ответственный | |
| Ветка | `main` |
| Тип | patch / minor / major |

---

## 1. Предварительные проверки (до сборки)

- [ ] Ветка `main` актуальна: `git pull origin main`
- [ ] Все нужные PR смёржены, нет незавершённых задач в `tasks/active/`
- [ ] `.env` на целевом сервере содержит все нужные переменные (см. `.env.example`)
  - `OPENROUTER_API_KEY`
  - `YOUTRACK_URL` / `YOUTRACK_TOKEN` / `YOUTRACK_PROJECT` (если используется)
  - `GITHUB_TOKEN` / `GITHUB_OWNER` / `GITHUB_REPO` (если используется)
- [ ] Тесты прошли локально: `cd workflow-core && gradle test`
- [ ] Playwright тесты прошли: `cd workflow-ui && npm run test`

---

## 2. Сборка Docker-образов

Проект собирается двухэтапным (multi-stage) build для каждого сервиса.

### Backend (`workflow-core`)

```
gradle:8.12-jdk21-alpine  →  bootJar  →  eclipse-temurin:21-jre-alpine
```

Что происходит в Dockerfile:
1. Зависимости скачиваются слоем-кэшем (`gradle dependencies`)
2. Исходники копируются, собирается fat-jar (`gradle bootJar -x test`)
3. В финальный образ добавляется `git` (нужен для клонирования репо в пайплайне)
4. Папка `config/` (YAML пайплайнов) копируется рядом с jar

> Порт внутри контейнера: **8020** (Spring Boot слушает 8020,
> `EXPOSE 8080` в Dockerfile — расхождение, фактически сервис поднимается на 8020
> через `application.yaml`).

### Frontend (`workflow-ui`)

```
node:20-alpine  →  npm ci + vite build  →  nginx:alpine
```

Что происходит в Dockerfile:
1. Зависимости ставятся через `npm ci` (воспроизводимый lock)
2. Vite билдит статику в `dist/`
3. Nginx раздаёт статику и проксирует `/api` и `/ws` на `workflow-core:8020`

---

## 3. Запуск через docker-compose

```bash
OPENROUTER_API_KEY=sk-or-... docker-compose up --build -d
```

Что поднимается:

| Сервис | Внутренний порт | Внешний порт | Зависит от |
|---|---|---|---|
| `workflow-core` | 8020 | **8020** | — |
| `workflow-ui` | 80 | **5173** | `workflow-core` |

Состояние базы H2 хранится в Docker volume `workflow-state` → `/app/.workflow`.
Данные переживают пересборку контейнеров.

Переменная окружения `WORKFLOW_MODE: gui` включает WebSocket approval gates
вместо CLI-режима.

---

## 4. Проверка после запуска

```bash
# Логи обоих сервисов
docker-compose logs -f

# Backend живой
curl http://localhost:8020/api/pipelines

# Frontend живой
curl -s http://localhost:5173 | head -5
```

Ожидаемые признаки успеха:
- [ ] Backend логи: `Started WorkflowApplication` без `ERROR`
- [ ] `GET /api/pipelines` возвращает JSON (не 502)
- [ ] `http://localhost:5173` открывается в браузере, нет ошибок в DevTools
- [ ] WebSocket подключение успешно: в Network-вкладке нет failed WS-запросов

---

## 5. Smoke-тест пайплайна

1. Открыть `http://localhost:5173`
2. Войти (логин по умолчанию печатается в логах бэкенда при первом старте)
3. Sidebar → **Пайплайны** → выбрать `feature`
4. Выбрать entry point, ввести тестовое требование
5. Нажать **Запустить**
6. Убедиться, что статус блоков обновляется в реальном времени (WebSocket работает)
7. Если есть блок с `approval: true` — проверить, что диалог одобрения появляется

---

## 6. Откат

```bash
# Остановить текущую версию
docker-compose down

# Переключиться на предыдущий тег
git checkout v<предыдущая>
docker-compose up --build -d
```

> Volume `workflow-state` остаётся нетронутым — данные сохранены.
> Если нужно сбросить базу: `docker volume rm ai-workflow_workflow-state`

---

## 7. Нотки по известным quirks

| Проблема | Причина | Решение |
|---|---|---|
| `EXPOSE 8080` в `workflow-core/Dockerfile`, но сервис на 8020 | Dockerfile устарел относительно `application.yaml` | Игнорировать EXPOSE, docker-compose маппит 8020→8020 корректно |
| Cyrillic paths в docker на Windows/MSYS | path-conversion ломает монтирование | Копировать в ASCII-путь перед монтированием |
| `*IT` тесты падают без ключа | `@EnabledIfEnvironmentVariable(OPENROUTER_API_KEY)` | Выставить переменную или запускать `gradle test` без IT-тестов |
| `workflow-ui` использует `proxy` к `:8020` в dev-режиме | `vite.config.ts` | В prod проксирует nginx (nginx.conf) |

---

## История выкатов

| Дата | Версия | Автор | Заметки |
|---|---|---|---|
|  |  |  |  |

# GUI quickstart

## Start the GUI

**Текущая конфигурация (гибридный режим):**
UI работает в Docker, бэкенд — локально через `gradle bootRun`.

```bash
# Бэкенд (локально):
cd workflow-core
gradle bootRun          # :8020

# UI (Docker):
docker compose up -d workflow-ui   # :5173
```

- Backend: `http://localhost:8020`
- Frontend: `http://localhost:5173`

**Только Docker (оба сервиса):**

```bash
docker compose up --build -d
```

**Только локально (dev):**

```bash
# Terminal 1 — backend
cd workflow-core
gradle bootRun          # :8020

# Terminal 2 — frontend
cd workflow-ui
npm install
npm run dev             # :5173, proxies /api and /ws to :8020
```

## Login

Пароль задаётся через `ADMIN_PASSWORD` в `.env` (по умолчанию `admin`).

Open `http://localhost:5173` and log in.

## Run a pipeline — GUI

1. **Sidebar → Пайплайны**
2. Select a pipeline from the right panel (e.g. `skill-marketplace`)
3. Select an entry point if multiple are listed
4. Fill in the required fields (e.g. path to task.md)
5. Click **Запустить** — you are redirected to the live run page

## Run a pipeline — REST API

Работает когда UI недоступен или для автоматизации. Бэкенд слушает на `:8020`.

```bash
# 1. Получить CSRF-токен
curl -s -c /tmp/wf_cookies.txt http://localhost:8020/api/auth/me > /dev/null
CSRF=$(grep XSRF /tmp/wf_cookies.txt | awk '{print $NF}')

# 2. Логин
curl -s -c /tmp/wf_cookies.txt -b /tmp/wf_cookies.txt \
  -X POST http://localhost:8020/api/auth/login \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"username":"admin","password":"admin"}'

# 3. Обновить CSRF (новый после логина)
CSRF=$(grep XSRF /tmp/wf_cookies.txt | awk '{print $NF}')

# 4. Список пайплайнов
curl -s -c /tmp/wf_cookies.txt -b /tmp/wf_cookies.txt \
  http://localhost:8020/api/pipelines \
  -H "X-XSRF-TOKEN: $CSRF"

# 5. Запустить пайплайн skill_marketplace (entry point from_task_file)
curl -s -c /tmp/wf_cookies.txt -b /tmp/wf_cookies.txt \
  -X POST http://localhost:8020/api/runs \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -d '{
    "configPath": "./config/skill_marketplace.yaml",
    "entryPointId": "from_task_file",
    "autoApproveAll": true,
    "inputs": {
      "task_file": "/Users/home/Code/skill_marketplace/tasks/active/SM-004_review-submission.md"
    }
  }'

# 6. Проверить статус запуска
RUN_ID="<runId from step 5>"
curl -s -c /tmp/wf_cookies.txt -b /tmp/wf_cookies.txt \
  "http://localhost:8020/api/runs/$RUN_ID" \
  -H "X-XSRF-TOKEN: $CSRF" | python3 -m json.tool
```

> **Важно:** `inputs.task_file` принимает абсолютный путь на хосте (не контейнерный).
> configPath — относительный от рабочей директории бэкенда (`workflow-core/`).

### Entry points skill_marketplace

| id | from_block | requires_input | Назначение |
|----|-----------|----------------|-----------|
| `from_task_file` | `task_md` | `inputs.task_file` — путь к .md файлу | Задача из файла → analysis → plan → codegen → review |
| `from_requirement` | `analysis` | `requirement` — текст требования | Новая задача с нуля |
| `code_only` | `codegen` | `requirement` | Только генерация кода |

## Monitor a run

The run page shows:

- Block progress table — each block with status icon
- Live log panel — streaming tool calls and LLM output
- Loopback timeline — if the run retried any blocks

## Approve a paused step

When a block has `approval: true`, the run pauses and the approval dialog
opens automatically:

| Action | Effect |
|--------|--------|
| **Одобрить** | Continue to the next block |
| **Редактировать** | Modify the block's JSON output before continuing |
| **Пропустить** | Skip this block |
| **Отклонить** | Stop the run |
| **Перейти** | Jump to a specific remaining block |

Paused runs also appear in **Активные запуски** with an amber "Рассмотреть"
link — useful for monitoring multiple concurrent runs.

## Active runs dashboard

**Sidebar → Активные запуски** shows all `RUNNING` and `PAUSED_FOR_APPROVAL`
runs in real time via WebSocket. Filter by status or pipeline name.
Completed runs fade out automatically; failed runs stay visible until refresh.

## Run tests

```bash
cd workflow-ui
npm run test        # Playwright UI tests (mock API, no backend required)
npm run test:ui     # Interactive Playwright UI mode
npm run test:e2e    # E2E tests (requires backend running)
```

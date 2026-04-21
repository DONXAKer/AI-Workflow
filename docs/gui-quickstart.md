# GUI quickstart

## Start the GUI

**With Docker (recommended):**

```bash
# In the AI-Workflow repo:
OPENROUTER_API_KEY=sk-or-... docker-compose up -d
```

- Backend: `http://localhost:8020`
- Frontend: `http://localhost:5120`

**For local development:**

```bash
# Terminal 1 — backend
cd workflow-core
gradle bootRun     # starts on :8020

# Terminal 2 — frontend
cd workflow-ui
npm install
npm run dev        # starts on :5120, proxies /api and /ws to :8020
```

## Login

Default credentials are printed in the backend logs on first start:

```
Bootstrap admin created — username: admin  password: <generated>
```

Open `http://localhost:5120` and log in.

## Run a pipeline

1. **Sidebar → Пайплайны**
2. Select a pipeline from the right panel (e.g. `feature`)
3. Select an entry point if multiple are listed
4. Fill in the required fields (e.g. path to task.md)
5. Click **Запустить** — you are redirected to the live run page

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

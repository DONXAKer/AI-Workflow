# Plan: Run Summary Panel + Commit Scope Fix

## Context

После прогона CRM-382 выявлены две проблемы:
1. **Commit затронул лишние репозитории** (crm/Initialcrm, smsandchequesender) — у них были старые незакоммиченные изменения, не связанные с задачей. Блок `commit` коммитил всё что нашёл через `find .git`, а не только то что сделал `chunk`.
2. **Нет сводного окна** — после завершения пайплайна непонятно: что сделано, какие сервисы затронуты, сколько файлов/строк.

---

## Часть 1 — Commit Scope Fix (agent-sdlc.yaml)

**Файл:** `workflow-core/config/agent-sdlc.yaml`, блок `commit`

Добавить `chunk` в `context_blocks` и изменить `user_message` — агент должен:
1. Взять список файлов из `chunk.files_changed`
2. По путям определить какие репозитории затронуты (prefix до первого `/src/` или до второго `/`)
3. Коммитить **только** эти репозитории, остальные игнорировать

Изменить строку в `user_message`:
```
Шаг 1 — Определи ТОЛЬКО репозитории, в которых chunk изменил файлы.
  Возьми paths из chunk.files_changed (контекст передан).
  По префиксу пути определи корень репозитория: crm/crm2/src/... → crm/crm2.
  Коммить ТОЛЬКО эти репо. Если в другом репо есть незакоммиченные изменения — игнорируй.
```

И добавить в config:
```yaml
context_blocks: [task_input, plan, chunk]   # добавить chunk
```

---

## Часть 2 — Run Summary Panel (UI)

### Новый компонент

**Файл:** `workflow-ui/src/components/RunSummaryPanel.tsx` (новый, ~150 строк)

Props: `{ run: PipelineRun }`

Читает из `run.outputs` (парсит как в RunPage, строки 121-145):
- `task_input.issue` → `{ readableId, summary, url }`
- `analysis.acceptance_checklist` → `[{ id, description, priority }]`
- `review.checklist_status` → `[{ id, passed, evidence }]`
- `chunk.files_changed` → `[{ path, insertions, deletions }]`
- `commit.final_text` → markdown-таблица с репозиториями

Парсинг репозиториев из `commit.final_text` (regex):
```
/\|\s*\*\*(.+?)\*\*\s*\|\s*`(.+?)`\s*\|\s*`(.+?)`/g
→ { repo, branch, hash }
```

### Структура секций

```
✅ CRM-382 — Корректировки. Заявки → вкладка ТТ

[Что сделано]
  ✅ critical   Добавлен CourierOrder enum
  ✅ important  Ветка ТТ отображает скидку приложения
  ❌ important  InputNumber для technology_358

[Затронутые сервисы]
  📦 crm/crm2           feature/CRM-382   6dc0bb82
  📦 crm/crm2_frontend  feature/CRM-382   579dbf3

[Изменения в коде]
  12 файлов   +347 строк   −28 строк
```

### Интеграция в RunPage

**Файл:** `workflow-ui/src/pages/RunPage.tsx`

RunPage уже имеет tab-систему. Добавить вкладку "Итоги" — видна только при `status === 'COMPLETED'`.

Найти место где объявлены tabs (поиск по `activeTab`) и добавить:
```tsx
{run.status === 'COMPLETED' && (
  <button onClick={() => setActiveTab('summary')} ...>Итоги</button>
)}
...
{activeTab === 'summary' && <RunSummaryPanel run={run} />}
```

### Файлы для изменения

| Файл | Изменение |
|------|-----------|
| `workflow-ui/src/components/RunSummaryPanel.tsx` | Новый компонент |
| `workflow-ui/src/pages/RunPage.tsx` | Tab "Итоги" + подключение RunSummaryPanel |
| `workflow-core/config/agent-sdlc.yaml` | commit: context_blocks + user_message |

## Проверка

1. Следующий прогон CRM-382 — commit затрагивает только crm/crm2 и crm/crm2_frontend
2. После COMPLETED → вкладка "Итоги" отображает checklist, репозитории, статистику файлов
3. Если `files_changed` пуст или `commit.final_text` не содержит таблицы — секции скрываются (graceful degradation)

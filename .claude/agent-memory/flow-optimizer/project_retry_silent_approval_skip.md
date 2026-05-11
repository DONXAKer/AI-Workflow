---
name: Retry endpoint silently skips approval gates
description: POST /api/runs/{id}/retry копирует все BlockOutput из source run и помечает completed; failed approval-блоки молча пропускаются в новом run (нет re-approval, нет re-execution)
type: project
---

`POST /api/runs/{id}/retry` (RunController.java:562-633) копирует все `BlockOutput` source run в `injectedOutputs` + `blockTimestamps`, потом удаляет только `failed.getCurrentBlock()`. Все остальные блоки попадают в `completedBlocks` через preEntry-loop в `PipelineRunner.runFrom()` (строки 191-214) и `executeBlocks(skipCompleted=true)` пропускает их через `continue`.

Кейс bug: source run `fa15f00d` упал по approval-timeout на `diff_review` (90 минут). Retry создал run `b732d484` со статусом COMPLETED за 60 секунд, 0 LLM-calls, 0 tool-calls. `diff_review` не показал approval-gate, `git_push`/`pr_link` не выполнились — но в UI run выглядит как успешный.

Корни:
1. `failed.getCurrentBlock()` ненадёжен для approval-timeout (поле может быть `null` или сдвинуто).
2. Нет фильтрации по статусу блока в source — failed/timeout блоки переносятся как completed.
3. Approval-gate'овые блоки переносятся без re-approval — silent skip safety control.
4. Timestamps копируются 1:1 → events нового run показывают время source run, ломая observability.

**Why:** обнаружено 2026-05-08 при анализе run-а b732d484. 0 LLM/tool вызовов + 60-секундный COMPLETED с `git_push`/`pr_link`/`diff_review` в completedBlocks — невозможно при честном выполнении.

**How to apply:** до фикса предупреждать оператора, что retry упавшего approval-блока — opt-in security risk. Использовать `/return` с явным `targetBlock` вместо `/retry` для approval-цепочек. Для diagnoses новых "instantly COMPLETED" retry-runs проверять `/llm-calls` и `/tool-calls` — пустые массивы = реального выполнения не было. См. также `feedback_review_verify_disagreement.md` — другая патология того же pipeline.

---
name: diff_review approval timeout — UI не оповещает оператора достаточно агрессивно
description: Run завершился после 1.5 часа на approval-gate `diff_review`, оператор не видел что pipeline ждёт его действия — нет push-нотификации, страница run-detail не сигнализирует
type: project
---

Run `fa15f00d-d513-4e48-9369-e27b83294ae6` (WarCard FEAT-PHASE-002, 2026-05-08): pipeline дошёл до `diff_review` (approval=true) около 12:00, упал по `Approval timeout` в 12:26 — оператор не отреагировал за 26 минут.

**Why:** WebSocketApprovalGate.request() (workflow-core/src/main/java/com/workflow/core/WebSocketApprovalGate.java:58) имеет hard timeout (по умолчанию ~30 мин). Если operator не нажал Approve в окне браузера — run автоматически падает FAILED. Это безопасное поведение (защита от навсегда зависших runs), но UX недостаточный:

1. STOMP `/topic/runs/{runId}` сообщает APPROVAL_REQUEST только тем клиентам, которые в этот момент подписаны. Если оператор закрыл вкладку — ничего не приходит.
2. На странице runs-list нет колонки "awaiting your approval" — оператор должен сам открыть run-detail чтобы увидеть статус PAUSED_FOR_APPROVAL.
3. Не отправляется внешняя нотификация (email/Slack/Telegram через `NotificationChannelRegistry`) — хотя инфраструктура есть.

**How to apply:**

P1 fix:
- Подцепить `NotificationChannelRegistry` в `WebSocketApprovalGate.request()` — отправлять APPROVAL_REQUIRED через все настроенные каналы при первом waiting и за 5 минут до timeout.
- В `RunsListPage` показывать badge "🔔 awaiting approval" на runs со статусом PAUSED_FOR_APPROVAL (через `runs/stats.awaitingApproval` уже есть в API — добавить в UI).
- На странице run-detail поднять модал поверх всего, не сворачиваемый, с countdown до timeout — чтобы оператор не пропустил.

P2 fix:
- Сделать timeout настраиваемым per-block (`approval_timeout_min: 60`) — `diff_review` для крупных feature'ов реально может требовать >30 мин на review кода человеком.
- Добавить feature: при timeout не падать сразу, а перевести в специальный статус `STALE_APPROVAL` (отделимый от FAILED), чтобы можно было resume через `/api/runs/{id}/approval`. Сейчас retry создаёт новый runId — теряется continuity.

Связано с: PipelineRejectedException (workflow-core/src/main/java/com/workflow/core/PipelineRejectedException.java).

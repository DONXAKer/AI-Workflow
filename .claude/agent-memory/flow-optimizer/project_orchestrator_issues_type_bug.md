---
name: Orchestrator review-mode issues field is String, PipelineRunner expects List → loopback always loses issues
description: Bug in OrchestratorBlock review-mode where computeReviewVerdict returns issues as joined String, but PipelineRunner casts to List<String> and falls back to List.of() — impl_server never sees what to fix on loopback
type: project
---

`OrchestratorBlock.runReview` (около строки 393) делает `result.put("issues", v.issues())`, где `v.issues()` — **String** (joined `String.join("\n", blockingMessages)`).

`PipelineRunner.java:940-941`:
```java
List<String> issues = output.get("issues") instanceof List<?> l
    ? (List<String>) l : List.of();
```

Если issues — String → instanceof List ложно → `List.of()` → impl_server получает `_loopback.issues=[]` пустым.

**Why:** обнаружено на run `e9ed9de8-3ec1-4e0c-87ea-8a9c9f81d063` (FEAT-PHASE-002, 2026-05-08): review_impl iter 0 нашёл `task-moved-to-done` failed с подробной evidence + fix. Loopback запущен. impl_server получил issues=[] и не знал что чинить → patho-loopback на 5 итераций impl × 3 итерации verify_acceptance.

**Status (2026-05-08):** ИСПРАВЛЕНО в `PipelineRunner.java`. На строках 940-943, 978-981, 1038-1041 теперь стоит fallback `output.get("issues") instanceof String s && !s.isBlank() ? List.of(s) : List.of()`. Все три места loopback'ов (verify orchestrator, agent_verify, on_failure CI) принимают и String и List.

**How to apply:** при отладке любого patho-loopback после review_impl/orchestrator-review:
1. Подтверждённый run, на котором баг проявлялся: `e9ed9de8-3ec1-4e0c-87ea-8a9c9f81d063` (FEAT-PHASE-002 первая попытка). После фикса (run `fa15f00d-d513-4e48-9369-e27b83294ae6`) review_impl loopIterations=0 — review проскочил с `passed=true`, без срабатывания loopback'а.
2. Если patho-loopback повторится в новых runs — это уже НЕ этот баг. Скорее всего: (а) reviewer system-prompt снисходителен к тем же items, что verify_acceptance строго отвергает (несогласованность критериев), (б) acceptance_checklist содержит непроверяемые items (см. feedback_acceptance_checklist_unverifiable_items.md), (в) CLI route без tool-use (см. project_cli_route_toolless.md).

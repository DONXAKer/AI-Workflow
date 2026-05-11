---
name: AgentVerifyBlock regression flags никогда не вычисляются на loopback
description: computeRegressionFlags читает _previous_verification_results из top-level input, но handleLoopback кладёт inject_context в input._loopback (вложенно), поэтому regression detection всегда пуст
type: project
---

`AgentVerifyBlock.computeRegressionFlags` (workflow-core/src/main/java/com/workflow/blocks/AgentVerifyBlock.java строка 387) делает `input.get("_previous_verification_results")` — но `PipelineRunner.handleLoopback` (строки 706-710 в PipelineRunner.java) сохраняет весь `inject_context` под ключом `_loopback_<targetBlock>` как BlockOutput, и потом `gatherInputs` читает его в `input["_loopback"]` (строки 397-407). То есть `_previous_verification_results` физически живёт по пути `input._loopback._previous_verification_results`.

**Why:** Из-за этого `regression_flags` в выводе `verify_acceptance` всегда `[]`, даже если impl_server между iterations N-1 и N сломал ранее работающее. Operator не видит «было PASS, стало FAIL» в UI. Pipeline всё равно упадёт корректно через `evaluatePassThreshold`, но без diagnostic value.

**How to apply:** Минимальный fix:
```java
Object prevRaw = input.get("_previous_verification_results");
if (prevRaw == null && input.get("_loopback") instanceof Map<?, ?> lb) {
    prevRaw = lb.get("_previous_verification_results");
}
```
Альтернативно: научить PipelineRunner.handleLoopback unwrap'ить keys с префиксом `_` обратно в top-level input (но это сломает другие inject_context, использующие подчёркивание).

Найдено 2026-05-08 при анализе `feature.yaml` верификации. Затрагивает любой YAML с `inject_context: _previous_verification_results: ...`. Severity: P2.

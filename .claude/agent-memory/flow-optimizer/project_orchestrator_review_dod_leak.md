---
name: Orchestrator review-mode injects Plan goal even with checklist (single-source-of-truth leak)
description: OrchestratorBlock.runReview всё ещё append'ит ## Plan goal когда haveChecklist=true, хотя prompt говорит "checklist — единственный источник истины"
type: project
---

В `OrchestratorBlock.runReview` (workflow-core/src/main/java/com/workflow/blocks/OrchestratorBlock.java строки 303-312) при `haveChecklist=true` инжектится `## Plan goal` из plan_block. Это противоречит system prompt'у в `buildReviewSystemPrompt` (строка 852): «Acceptance checklist в user-message — ЕДИНСТВЕННЫЙ источник истины. Оцениваешь ТОЛЬКО эти id».

**Why:** Reviewer (Sonnet/GLM-4.6) при наличии Plan goal начинает оценивать «соответствие goal» помимо checklist'а — добавляет noise в issues, может ставить passed=false по причинам вне checklist'а. Нарушает PR1+PR2 design.

**How to apply:** При следующей правке `OrchestratorBlock` обернуть инжекцию `Plan goal`/`Definition of Done` в `else if (!haveChecklist)`. Когда checklist есть — **только** checklist + diff. Goal/DoD только в legacy fallback. Заодно проверить `context_blocks` инжекцию (строки 314-322) — она тоже может тащить goal.

Найдено 2026-05-08 при статическом анализе. Severity: P2 (cost + reviewer drift, не блокер).

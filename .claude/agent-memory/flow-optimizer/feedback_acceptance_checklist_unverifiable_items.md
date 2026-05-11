---
name: acceptance_checklist must contain only items verifiable by Read/Grep/Glob/Bash inside the repo
description: When analysis-block generates acceptance_checklist with post-pipeline state (commit/merge), GUI-only checks (UE Editor BP), or build-success-without-CI items, agent_verify cannot find evidence and pipeline patho-loops
type: feedback
---

acceptance_checklist (создаваемый smart-tier analysis) должен содержать ТОЛЬКО пункты, проверяемые через Read/Grep/Glob/Bash в репозитории кода ДО commit-а.

**Why:** на run `e9ed9de8-3ec1-4e0c-87ea-8a9c9f81d063` (WarCard FEAT-PHASE-002, 2026-05-08) analysis сгенерировал checklist, где из 10 пунктов 3 были непроверяемы:
- `project-compiles` — требует UnrealBuildTool log, у CLI нет shell
- `task-moved-to-done` — требует git-merge, которого pipeline ещё не сделал (commit-блок идёт ПОСЛЕ verify_acceptance в этом флоу)
- `diceroll-rename-no-bp-break` — требует UE Editor + бинарные .uasset файлы, ripgrep их пропускает

Verify-блок 3 итерации подряд репортил FAIL с тем же evidence-of-absence ("Bash unavailable; CLAUDE.md says user builds manually"). Loopback в impl_server бесполезен — implementor не может ни запустить UE Editor, ни выполнить commit/merge сам.

**How to apply:** при настройке analysis-блока в feature.yaml добавлять в `agent.systemPrompt` явный запрет:
```
acceptance_checklist должен содержать ТОЛЬКО пункты, проверяемые через
Read/Grep/Glob/Bash в репозитории кода. Запрещено включать:
- "проект компилируется в IDE" (без CI-лога / build-output файла)
- "Blueprint открывается без ошибок" (требует UE Editor)
- "ветка squash-смержена" / "task.md перемещён в done"
  (это делает pipeline ПОСЛЕ verify_acceptance)
- любые runtime-condition'ы, требующие GUI/специфичных инструментов
Такие требования упоминай в `risks` или `open_questions`, но НЕ
в acceptance_checklist. Все элементы checklist должны иметь конкретное
grep-pattern или test-name, по которому agent_verify может найти доказательство.
```

Альтернатива: ввести проверку в AnalysisBlock на post-process этапе — отбрасывать checklist-items без `evidence_pattern` или с явными trigger-словами ("squash-merge", "compiles", "BP-нод"). Но проще через prompt.

Связанная проблема: review_impl при тех же пунктах поощряется к снисходительности ("уже OK") system-prompt'ом OrchestratorBlock — несогласованность с verify_acceptance строгостью. Чинить вместе.

---
name: review_impl и verify_acceptance расходятся по строгости — review говорит passed=true, verify тут же фейлит критические items
description: На одних и тех же checklist items review_impl ставит passed=true (мягко), а agent_verify тут же ставит FAIL (строго). Это вызывает loopback в impl_server, который ничего не может сделать (требования некодифицируемые)
type: feedback
---

Если в одном run review_impl говорит `passed=true` для item X, а verify_acceptance тут же говорит `status=FAIL` для того же X — это сигнал что **критерий не кодифицируем**, не баг impl-блока.

**Why:** на run `fa15f00d-d513-4e48-9369-e27b83294ae6` (WarCard FEAT-PHASE-002 retry, 2026-05-08):
- `task-moved-to-done` (priority=important): review_impl R2..R85 итерация говорил passed=true c evidence "файл существует на диске"; verify_acceptance параллельно говорил FAIL с evidence "git status показывает файл untracked, ветка не смержена в main". Оба правы по своей семантике — review проверяет факт существования (что в репо есть пункт), verify проверяет git history (которого ещё нет, т.к. commit/git_push идут ПОСЛЕ).
- `project-compiles`, `diceroll-rename-no-bp-break`: review засчитывал "C++ синтаксически корректен / EnumValueRedirects настроен" как доказательство; verify требовал build-log из UnrealBuildTool / открытый UE Editor.

**How to apply:**

1. При проектировании acceptance_checklist (через analysis system_prompt в `feature.yaml`) запрещать item'ы, у которых evidence лежит ВНЕ репозитория во время выполнения impl_server (post-commit state, GUI-tool output, build-log). Текущий запрет в `feature.yaml:66-75` НЕ работает — проверено в run2: те же 3 непроверяемых item'а попали в checklist. Нужно либо ужесточить промпт, либо ввести post-process валидатор в `AnalysisBlock` который отбрасывает items с trigger-фразами ("squash-merge", "compiles", "BP-нод", "UE Editor", "task moved to done", "пользователь уведомлён").

2. Привести reviewer prompt в `OrchestratorBlock` (review mode) к тому же стандарту строгости, что и `agent_verify`. Сейчас review снисходителен — он засчитывает intent (`EnumValueRedirects настроен → "no BP break"`), а verify требует факт (`UE Editor запущен → BP компилируется`). Оба должны принимать одинаковую evidence-policy: либо оба требуют материальное доказательство, либо оба засчитывают "по конструкции". Лучше — первое.

3. В review-mode выпустить новое правило в reviewer-prompt: «если acceptance_checklist item требует state, который impl-блок физически не может создать (post-commit git state, build artifact, UE Editor output) — НЕ ставь passed=true. Поставь passed=false с fix='non-derivable from repo, requires post-pipeline action'. Это будет escalate на operator approval gate, а не патологический loopback в impl_server.»

**Related runs:**
- e9ed9de8 (run1) — патологический loopback на этих же items
- fa15f00d (run2) — review_impl проскочил (passed=true) после фикса instanceof-bug, но verify_acceptance всё ещё фейлил → 2 итерации loopback impl_server → manual override approval. Pipeline в итоге дошёл до diff_review только потому что operator руками одобрил manual override.

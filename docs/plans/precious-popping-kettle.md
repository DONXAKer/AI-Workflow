# Phase-based block ordering в Pipeline Editor

## Context

Сейчас в визуальном редакторе пайплайна (`workflow-ui/src/components/PipelineEditor/`) блоки можно ставить в произвольной последовательности — `BlockPalette` показывает все 31 типов сгруппированными по `category` (input/agent/verify/ci/...), popover «+ после блока X» в `index.tsx:258` показывает весь registry без фильтрации. Ничто не мешает воткнуть `deploy` до `run_tests` или `verify` после `release_notes`. Валидатор (`PipelineConfigValidator`) проверяет структуру/граф/data flow, но не проверяет «фазовый» смысл.

Фича вводит понятие **фазы** (linear ordering): каждый блок относится к одной из 6 фаз (`INTAKE → ANALYZE → IMPLEMENT → VERIFY → PUBLISH → RELEASE`) или помечен как `ANY` (polymorphic). Валидатор проверяет монотонность по `depends_on`, UI фильтрует доступные блоки и предлагает шаблоны. Цель двойная: (1) не дать построить семантически нелепый pipeline, (2) ускорить создание новых пайплайнов через готовые скелеты.

Дизайн зафиксирован grilling-сессией (10 развилок). Существующие YAML мигрируются вручную в этом же PR.

## Scope

**В scope:**
- Backend: `Phase` enum, поле `BlockMetadata.phase`, `BlockConfig.phase` (per-instance override), Level 4 правило в `PipelineConfigValidator`.
- Frontend: фильтр в popover «+после X», phase-grouping в `BlockPalette`, поле «Фаза» в `SidePanel`, цветная полоска фазы на `BlockNode`, индикатор пропущенных фаз в Toolbar, 3 шаблона в `NewPipelineModal`.
- Migration: 5 in-repo YAML с расстановкой `phase:` где default неприменим.

**Не в scope (Phase 2):**
- UI-wizard для миграции внешних YAML (для WarCard и др. — пользователи увидят ERROR'ы и поправят руками).
- Индикатор «фаза override'нута» (значок ⚙ на блоке).
- Composite blocks / blueprints как новый тип блока.

## Архитектурные решения (фиксированы grilling-сессией)

1. **6 фаз + ANY:** `INTAKE → ANALYZE → IMPLEMENT → VERIFY → PUBLISH → RELEASE`, `ANY` для polymorphic.
2. **Source of truth:** Java enum + `BlockMetadata.phase` дефолт + per-instance YAML override `phase:` top-level.
3. **Семантика «после»:** монотонность по `depends_on` (`phase(v) ≥ max(phase(предки))`); loopback `on_fail.target` требует `phase(target) < phase(self)`; `condition:` блоки не участвуют.
4. **Severity:**
   - Нарушение монотонности `depends_on` → **ERROR** (новый код `PHASE_MONOTONICITY`).
   - Loopback target в более позднюю/равную фазу → **ERROR** (`PHASE_LOOPBACK_FORWARD`).
   - Polymorphic блок (`ANY`) без override → **WARN** (`PHASE_OVERRIDE_MISSING`).
   - Default phase = general (тип без фазы) → **INFO** (`PHASE_UNDEFINED`).
5. **Escape hatch:** top-level YAML флаг `phase_check: false` отключает Level 4 для конкретного pipeline (нужен для legacy/экзотики).
6. **UI guidance уровень B:** фильтр в popover + phase-templates + missing-phase indicator + цветовая полоска.
7. **Маппинг 31 блока:**
   - INTAKE (7): `business_intake`, `git_branch_input`, `mr_input`, `task_input`, `task_md_input`, `youtrack_input`, `youtrack_tasks_input`.
   - ANALYZE (3): `analysis`, `clarification`, `youtrack_tasks`.
   - IMPLEMENT (4): `agent_with_tools`, `claude_code_shell`, `code_generation`, `test_generation`.
   - VERIFY (5): `agent_verify`, `ai_review`, `build`, `run_tests`, `verify`.
   - PUBLISH (5): `github_actions`, `github_pr`, `gitlab_ci`, `gitlab_mr`, `vcs_merge`.
   - RELEASE (4): `deploy`, `release_notes`, `rollback`, `verify_prod`.
   - ANY (3): `http_get`, `orchestrator`, `shell_exec`.
8. **`ValidationError` сейчас не имеет поля `severity`** (`workflow-core/src/main/java/com/workflow/config/ValidationError.java:11`). Нужно расширить record полем `severity: ERROR | WARN | INFO`. Существующие call-sites ничего не сломает (Jackson auto-serializes), pre-run gate в `RunController.java:157` будет блокировать только на `severity=ERROR`.

## Backend changes

### Новые файлы
- `workflow-core/src/main/java/com/workflow/blocks/Phase.java` — enum `INTAKE, ANALYZE, IMPLEMENT, VERIFY, PUBLISH, RELEASE, ANY` + `compareOrder(Phase a, Phase b)` helper. `ANY` имеет специальную семантику (см. § Validator).

### Изменения в существующих файлах

**`workflow-core/src/main/java/com/workflow/blocks/BlockMetadata.java:24-40`**
- Добавить параметр `Phase phase` в record (после `category`).
- В `defaultFor(String name)` дефолт `phase = Phase.ANY` (с warn-семантикой при отсутствии override).

**`workflow-core/src/main/java/com/workflow/blocks/Block.java:23-25`**
- Default `getMetadata()` остаётся, но через `BlockMetadata.defaultFor(name)` который теперь даёт `Phase.ANY`.

**`workflow-core/src/main/java/com/workflow/blocks/*Block.java` (31 файл)**
- Каждый блок добавляет `phase` в `getMetadata()` согласно маппингу в § 7.
- Блоки без переопределённого `getMetadata()` (например, `DeployBlock`) — добавить полное `getMetadata()` с конкретной фазой, иначе они попадут в `ANY` и будут warn'иться.
- Шаблон правки в `OrchestratorBlock.java:119-142` — добавляется `Phase.ANY` четвёртым параметром.

**`workflow-core/src/main/java/com/workflow/config/BlockConfig.java`**
- Новое поле `String phase` с `@JsonProperty("phase")` + getter/setter.
- При null — используется `BlockMetadata.phase` соответствующего типа блока.
- Парсинг строки в enum (`Phase.valueOf(phase.toUpperCase())`) — отдельный helper, обрабатывает null/неизвестное значение (последнее → ValidationError).

**`workflow-core/src/main/java/com/workflow/config/PipelineConfig.java`**
- Новое поле `Boolean phaseCheck` (default `true`).
- Если `false` — Level 4 пропускается полностью.

**`workflow-core/src/main/java/com/workflow/config/ValidationError.java:11`**
- Расширить record полем `Severity severity` (enum `ERROR, WARN, INFO`).
- Помечать существующие ошибки как `ERROR` (back-compat).
- Добавить статические фабрики: `ValidationError.error(...)`, `.warn(...)`, `.info(...)`.

**`workflow-core/src/main/java/com/workflow/config/PipelineConfigValidator.java:190-261`**
- Добавить **Level 4** после Level 3 (можно даже параллельно — не зависит от Level 3 reference resolution).
- Алгоритм:
  1. Если `config.phaseCheck == false` → пропуск.
  2. Для каждого блока вычислить `effectivePhase`: `block.phase` если есть, иначе `metadata.phase`. Если `ANY` без override — добавить WARN `PHASE_OVERRIDE_MISSING`.
  3. Для каждого ребра `depends_on` u→v: если `effectivePhase(u) != ANY && effectivePhase(v) != ANY && order(v) < order(u)` → ERROR `PHASE_MONOTONICITY`.
  4. Для каждого блока с `on_failure.action == LOOPBACK`: `target = on_failure.target`; если `order(target) >= order(block)` (для ненулевых фаз) → ERROR `PHASE_LOOPBACK_FORWARD`.
  5. То же для `verify.on_fail.target` если `verify.on_fail.action == LOOPBACK`.
- `condition:` блоки участвуют наравне (мы решили: фаза проверяется всегда, скип в runtime — отдельная история).
- ANY блоки: их фаза «прозрачна» — пара `(u=ANY, v)` или `(u, v=ANY)` пропускается.
- Использовать существующий `topologicalOrder()` (`PipelineConfigValidator.java:331`) только для подтверждения отсутствия cycle (Level 4 не запускается при cycle, как и Level 3).

**`workflow-core/src/main/java/com/workflow/api/BlockRegistryController.java:33-46`**
- Изменений в коде нет — Jackson сам сериализует `BlockMetadata.phase` в JSON ответ. Добавить только тест на наличие поля `phase` в ответе.

### Тесты
**`workflow-core/src/test/java/com/workflow/config/PipelineConfigValidatorTest.java`** — добавить методы:
- `phaseDecreasesAlongDependsOn_emitsPhaseMonotonicity()` — `deploy` зависит от `analysis`, ждём ERROR.
- `phaseRespected_noErrors()` — корректный pipeline `intake → analyze → implement → verify → publish → release`.
- `loopbackToLaterPhase_emitsPhaseLoopbackForward()` — `verify.on_fail.target = release_notes`, ждём ERROR.
- `polymorphicWithoutOverride_emitsWarn()` — `shell_exec` без `phase:`, ждём WARN.
- `polymorphicWithOverride_noWarn()` — `shell_exec` с `phase: verify`, без warn.
- `phaseCheckDisabled_skipsLevel4()` — `phaseCheck: false`, нарушения игнорируются.
- `anyBlockTransparentInDependsOn_noErrors()` — `analysis → orchestrator(ANY) → deploy` валидно (orchestrator прозрачен).
- `unknownPhaseString_emitsValidationError()` — `phase: bogus` → ERROR `INVALID_PHASE`.

## Frontend changes

### Новые файлы
- `workflow-ui/src/components/PipelineEditor/PhaseSelector.tsx` — dropdown «Фаза» для `SidePanel`. 7 опций (6 фаз + «Default из metadata»). Кнопка «вернуть к default» если override активен.
- `workflow-ui/src/utils/phaseColors.ts` — палитра 7 цветов (приглушённые slate-tinted), функция `phaseColor(phase: string): string` возвращающая Tailwind класс.

### Изменения в существующих файлах

**`workflow-ui/src/types.ts:485-497`**
- `BlockMetadataDto`: добавить `phase: 'INTAKE' | 'ANALYZE' | 'IMPLEMENT' | 'VERIFY' | 'PUBLISH' | 'RELEASE' | 'ANY'`.
- `BlockConfigDto:395-421`: добавить `phase?: string` (опциональный override).
- `PipelineConfigDto`: добавить `phase_check?: boolean`.
- `ValidationError:285-297`: добавить `severity: 'ERROR' | 'WARN' | 'INFO'`.

**`workflow-ui/src/components/PipelineEditor/BlockPalette.tsx:12-22`**
- Заменить `CATEGORY_LABEL`/`CATEGORY_ORDER` на `PHASE_LABEL`/`PHASE_ORDER`.
- Группировка по `e.metadata.phase` вместо `e.metadata.category`.
- ANY-блоки в отдельной группе «Универсальные».

**`workflow-ui/src/components/PipelineEditor/index.tsx:80-87, 250-275`**
- В обработчике события `pipeline-editor:add-after` сохранить `afterBlockId` и подсчитать `effectivePhase` блока (helper из `effectivePhase(block, registry)`).
- В popover (строки 258-272): фильтрация registry по `phase >= afterPhase`. ANY-блоки всегда показываются.
- В кнопке drop из левой палетки на пустой Canvas (`addBlockFromRegistry(entry, null)` строка 217) — фильтра нет.

**`workflow-ui/src/components/PipelineEditor/Canvas.tsx:113-204`**
- В `buildGraph()`: добавить `phase` в `BlockNodeData` (вычислять как `block.phase ?? registry[block.block].metadata.phase`).
- При создании edge через user drag (`onConnectDependsOn` в `index.tsx:244`) — после успешного создания триггерить `validate()` чтобы Level 4 ошибки появились немедленно.

**`workflow-ui/src/components/PipelineEditor/BlockNode.tsx:23-93`**
- Добавить top-stripe div с `bg-{phaseColor}` вверху ноды (3-4px высота).
- В data приходит `phase` (из `Canvas.tsx`), `phaseColor()` → класс.

**`workflow-ui/src/components/PipelineEditor/SidePanel.tsx:123-180`**
- В `CommonFields` после поля `condition` добавить `<PhaseSelector value={block.phase} default={registryEntry.metadata.phase} onChange={p => onPatch({ phase: p })}/>`.

**`workflow-ui/src/components/PipelineEditor/Toolbar.tsx`**
- Новый sub-component `MissingPhasesIndicator` — список фаз, не представленных в pipeline (как `Set<Phase>`). Стиль: маленький жёлтый бейдж с tooltip «Нет блоков фазы verify, publish».
- Не блокирует save/run — только индикация.

**`workflow-ui/src/components/PipelineEditor/index.tsx:326-415` (NewPipelineModal)**
- Добавить radio-выбор шаблона: **Empty / Standard feature / Custom from phases**.
- `Empty` — текущее поведение (создаёт пустой YAML).
- `Standard feature` — backend resource `src/main/resources/pipeline-templates/standard-feature.yaml` (новый файл, точно по образцу из § 8 ниже).
- `Custom from phases` — расширить modal: чекбоксы для 6 фаз + dropdown «дефолтный блок» для каждой включённой фазы. На submit фронт собирает YAML-pipeline-список с `depends_on` на предыдущую фазу.

**`workflow-ui/src/services/api.ts:180`**
- `createPipeline({ slug, displayName, description, template?: 'empty' | 'standard' | 'custom', customSpec?: ... })`.
- Endpoint `POST /api/pipelines/new` принимает новые поля.

**Backend часть для template:**
- `workflow-core/src/main/resources/pipeline-templates/standard-feature.yaml` — новый файл (см. § 8).
- `workflow-core/src/main/java/com/workflow/api/PipelineConfigController.java` (или где `POST /api/pipelines/new`) — обработка `template` и `customSpec`.

### Тесты
**`workflow-ui/tests/ui/pipeline-editor-phase.spec.ts`** (новый файл) — добавить:
- `popover filters by phase` — добавить `analysis` в Canvas, кликнуть «+после», убедиться что `deploy` есть, а `task_md_input` нет (intake < analyze).
- `phase override is saved` — выбрать `shell_exec`, в SidePanel выбрать `phase: verify`, save → проверить body содержит `phase: 'verify'`.
- `phase color stripe visible` — снапшот класса top-stripe для каждой фазы.
- `missing phases indicator` — pipeline только с intake+analyze показывает индикатор «Нет: implement, verify, publish, release».

## Standard feature template

Новый файл `workflow-core/src/main/resources/pipeline-templates/standard-feature.yaml`:

```yaml
name: "Standard feature pipeline"
description: "Готовый скелет с 6 фазами"
phase_check: true

pipeline:
  - id: intake
    block: task_md_input
    phase: intake
    depends_on: []

  - id: analysis
    block: analysis
    phase: analyze
    depends_on: [intake]

  - id: clarification
    block: clarification
    phase: analyze
    depends_on: [analysis]

  - id: implement
    block: agent_with_tools
    phase: implement
    depends_on: [clarification]

  - id: verify
    block: agent_verify
    phase: verify
    depends_on: [implement]

  - id: publish
    block: github_pr
    phase: publish
    depends_on: [verify]

  - id: release_notes
    block: release_notes
    phase: release
    depends_on: [publish]
```

## Migration существующих YAML

Все 5 in-repo YAML мигрируются в этом же PR (правки минимальны: только override на нестандартных инстансах + ANY-инстансах).

| Файл | Правка |
|---|---|
| `workflow-core/src/main/resources/config/feature.yaml` | `build`/`tests`/`commit` (все `shell_exec`) → добавить `phase: verify` / `phase: verify` / `phase: release`. |
| `workflow-core/config/skill_marketplace.yaml` | `git_setup`/`build_test`/`git_commit` (`shell_exec`) → `phase: verify`/`phase: verify`/`phase: release`. `plan` (`orchestrator`) → `phase: analyze`. `review` (`orchestrator`) → `phase: verify`. |
| `workflow-core/src/main/resources/config/pipeline.full-flow.yaml` | `build` (`build`-блок после merge) → `phase: release`. `acceptance` (`run_tests` после deploy_test) → `phase: release`. |
| `config/pipeline.example.yaml` | проверить и при необходимости override (вероятно ничего не нужно). |
| `docs/feature-pipeline-template.yaml` | проверить и при необходимости override. |

После миграции запустить `gradle test --tests 'com.workflow.config.*'` — все YAML должны пройти валидацию без warnings (для in-repo) или с минимальными warns.

## Verification

1. **Backend unit + integration:**
   ```bash
   cd workflow-core
   gradle test --tests 'com.workflow.config.PipelineConfigValidatorTest'
   gradle test --tests 'com.workflow.config.FeaturePipelineConfigTest'
   gradle build  # full unit run, исключая *IT
   ```
   Все тесты зелёные. Новые тесты Level 4 покрывают 8 сценариев.

2. **Backend smoke API:**
   ```bash
   gradle bootRun
   curl http://localhost:8020/api/blocks/registry | jq '.[0].metadata.phase'  # должен вернуть фазу
   curl -X POST http://localhost:8020/api/pipelines/validate \
     -H 'Content-Type: application/json' \
     -d '{"configPath":"config/feature.yaml"}' | jq '.errors'  # должно быть [] или WARNы
   ```

3. **Frontend build + tests:**
   ```bash
   cd workflow-ui
   npm run build  # tsc + vite, без ошибок
   npm test       # Playwright UI tests, включая новый pipeline-editor-phase.spec.ts
   ```

4. **Manual e2e в editor:**
   - Открыть `/projects/default/settings` → Pipeline.
   - Создать новый pipeline с template `Standard feature` — должен открыться 7-блочный скелет, цветные полоски всех фаз видны.
   - Выбрать `analysis` блок, нажать «+ после» — popover показывает только блоки фазы analyze/implement/verify/publish/release + ANY (нет intake-блоков).
   - Перенести `deploy` мышкой и попробовать соединить с `analysis` через drag edge — должна появиться красная подсветка ошибки `PHASE_MONOTONICITY` в SidePanel.
   - В SidePanel `shell_exec`-блока выбрать `phase: verify` — баннер WARN исчезает.
   - В Toolbar при удалении блока `verify` появляется индикатор «Нет фазы: verify».

5. **Migration verification:**
   - Все 5 in-repo YAML после правок: `gradle test --tests '*PipelineConfigValidatorTest' --tests '*FeaturePipelineConfigTest'` → 0 ERRORs.
   - Открыть каждый из них в UI editor — нет красных бейджей на блоках.

## Implementation order (рекомендация)

PR1 — фундамент (можно мерджить отдельно):
- `Phase` enum, `BlockMetadata.phase`, маппинг 31 блока, `BlockConfig.phase`, registry-эндпоинт автоматически отдаёт phase.
- Frontend: типы обновлены, но UI ещё не использует — feature-flag, дефолт OFF.
- Migration in-repo YAML.

PR2 — валидатор:
- `ValidationError.severity`, Level 4 в `PipelineConfigValidator`, 8 unit-тестов.
- Pre-run / on-save / explicit gate уже подключены автоматически.

PR3 — UI:
- BlockPalette regroup, popover filter, SidePanel PhaseSelector, BlockNode top-stripe.
- Тесты Playwright.

PR4 — UX helpers:
- `MissingPhasesIndicator`, шаблоны в `NewPipelineModal`, `standard-feature.yaml` resource.

Каждый PR можно ревьюить и мерджить независимо. PR2 без PR1 не работает; PR3/PR4 предполагают наличие PR1 + PR2.

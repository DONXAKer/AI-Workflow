# Pipeline Editor v3 — Implementation Plan (PR-1 + PR-2)

Согласован grill-сессией 2026-05-07. Решения зафиксированы в `memory/project_pipeline_editor_v3.md`. Этот файл — детальный пошаговый план реализации первых двух PR из четырёх.

## Контекст

Переделка UI-редактора пайплайнов. Жалоба пользователя: текущий редактор перегружен (особенно side-panel блока) и неудобен для casual-пользователя.

**Глобальная карта:**

- **PR-1: Backend metadata extension** — фундамент (этот план)
- **PR-2: Side-panel restructure** — первый видимый результат, лечит «перегруз» (этот план)
- **PR-3: Creation wizard** — отдельный план потом
- **PR-4: Docs `adding-a-block.md`** — отдельно, ~½ дня

## Findings (отличия от изначального дизайна)

При чтении кода обнаружено:

1. **`FieldSchema` и `BlockMetadata` — Java `record`-ы**, не POJO. Расширение через canonical-constructor + compact-constructor с дефолтами; статические фабрики (`string`, `requiredString`, …) остаются byte-compatible.
2. **`BlockMetadata` имеет 5-arg backwards-compat constructor** — сохранить и делегировать в новый 8-arg.
3. **3 из топ-10 блоков (`github_pr`, `gitlab_mr`, `run_tests`) вообще не имеют `getMetadata()`** — наследуют от `Block.defaultFor()`. Им нужен полноценный override с нуля.
4. **`OrchestratorBlock` mode-dependent**: plan-mode и review-mode возвращают разные ключи. Декларируем union (false-positives на ссылках допустимы для PR-1; PR-3-wizard может стратифицировать).
5. **`AnalysisBlock` и `CodeGenerationBlock` имеют пустые `configFields`** — но `outputs` будут полноценные.
6. **`VerifyForm`** не использует `FieldSchema` — рукописная под `block.verify`. Альтернатива split-на-три: оставить целиком в Essentials, вынести только `OnFailEditor` как отдельный компонент для ConditionsAndRetrySection.
7. **`OutputsRefPicker` через `<datalist>`** для v1 (простой, работает в Playwright). Кастомный popover — если UX-фидбэк потребует.

---

## PR-1: Backend metadata extension

### Step 1.1 — Extend `FieldSchema` with `level` field

**File:** `workflow-core/src/main/java/com/workflow/blocks/FieldSchema.java`

- Add 8th component `String level` to canonical record header.
- Compact constructor: if `level == null`, default to `required ? "essential" : "advanced"`.
- Update factories (`string`, `requiredString`, `multilineString`, `monospaceString`, `number`, `bool`, `stringArray`, `enumField`, `blockRef`, `toolList`) — каждая передаёт `null` для level (compact constructor сам auto-resolve).
- Add convenience builder/factory variant для callers, которые хотят явно задать `level`.

**Effort:** 0.5h

### Step 1.2 — Extend `BlockMetadata` with `outputs` and `recommendedRank`

**File:** `workflow-core/src/main/java/com/workflow/blocks/BlockMetadata.java`

- Add components `List<FieldSchema> outputs` и `int recommendedRank`.
- Compact constructor: `outputs == null → List.of()`; `recommendedRank` — primitive, дефолт `0`.
- Сохранить 5-arg и 6-arg backwards-compat constructors; делегировать в canonical 8-arg с `outputs=List.of(), recommendedRank=0`.
- Обновить `defaultFor(String name)` — пустые outputs, rank=0.

**Effort:** 0.5h

### Step 1.3 — Update top-10 block `getMetadata()` overrides

Для каждого блока — заполнить `outputs` (читать `run()`, перечислить `result.put(...)` ключи), проставить `recommendedRank`. Где применимо — пометить existing `configFields` явным `level: "essential"`.

| Block | Action | recommendedRank | Notes |
|---|---|---|---|
| `AnalysisBlock` | + outputs (8 keys) | 100 | configFields пустые |
| `CodeGenerationBlock` | + outputs (6 keys) | 100 | configFields пустые |
| `VerifyBlock` | + outputs (8 keys) | 80 | |
| `AgentWithToolsBlock` | + outputs (7 keys) + level на configFields | 100 | `user_message`, `working_dir`, `allowed_tools` → essential; `bash_allowlist`, `max_iterations`, `budget_usd_cap`, `preload_from` → advanced |
| `OrchestratorBlock` | + outputs (union plan+review) + level | 70 | `mode`, `context_blocks`, `plan_block` → essential |
| `GitHubPRBlock` | **ADD** `getMetadata()` from scratch | 100 | `phase=PUBLISH`, `category="output"`, `configFields=[]` |
| `GitLabMRBlock` | **ADD** `getMetadata()` from scratch | 100 | `phase=PUBLISH`, `category="output"`, `configFields=[]` |
| `RunTestsBlock` | **ADD** `getMetadata()` from scratch | 80 | `phase=VERIFY`, `category="verify"`, configFields: `type` (enum), `environment`, `suite`, `timeout_seconds` |
| `AgentVerifyBlock` | + outputs (13 keys) + level | 90 | `subject`, `working_dir`, `pass_threshold` → essential |
| `TaskMdInputBlock` | + outputs (~13 keys включая needs_* heuristics) + level | 100 | `file_path` → essential |

**Outputs детализация (для исполнителя):**

- **AnalysisBlock**: `summary`, `affected_components`, `technical_approach`, `estimated_complexity` (enum: low|medium|high), `risks`, `open_questions`, `acceptance_checklist`, `needs_clarification`
- **CodeGenerationBlock**: `branch_name`, `changes`, `test_changes`, `commit_message`, `tasks_generated`, `youtrack_issues`
- **VerifyBlock**: `passed`, `score`, `checks_passed`, `checks_failed`, `issues`, `subject_block`, `iteration`, `recommendation`
- **AgentWithToolsBlock**: `final_text`, `stop_reason`, `iterations_used`, `total_input_tokens`, `total_output_tokens`, `total_cost_usd`, `tool_calls_made`
- **OrchestratorBlock (union)**: `goal`, `files_to_touch`, `approach`, `definition_of_done`, `tools_to_use`, `requirements_coverage`, `mode`, `iterations_used`, `total_cost_usd`, `passed`, `issues`, `action`, `retry_instruction`, `carry_forward`, `checklist_status`, `regressions`
- **GitHubPRBlock**: `pr_number`, `pr_url`, `branch`, `title`, `youtrack_issues_linked`, `created_at`
- **GitLabMRBlock**: `mr_id`, `mr_url`, `branch`, `title`, `youtrack_issues_linked`
- **RunTestsBlock**: `type`, `environment`, `suite`, `tests_run`, `tests_passed`, `tests_failed`, `failed_tests`, `status`, `report_url`, `finished_at`
- **AgentVerifyBlock**: `passed`, `verification_results`, `failed_items`, `passed_items`, `issues`, `iteration`, `recommendation`, `regression_flags`, `subject_block`, `pass_threshold`, `stop_reason`, `iterations_used`, `total_cost_usd`
- **TaskMdInputBlock**: `feat_id`, `slug`, `title`, `body`, `as_is`, `to_be`, `out_of_scope`, `acceptance`, `needs_bp`, `needs_server`, `needs_client`, `needs_contract_change`, `is_greenfield`

**Effort:** ~3.5h (15–25 min × 10 блоков)

### Step 1.4 — Extend `PipelineConfigValidator` with output-field check

**File:** `workflow-core/src/main/java/com/workflow/config/PipelineConfigValidator.java`

- New error code `REF_UNKNOWN_FIELD`, severity **WARN** (по spec — backwards-compat).
- В `validateRefs(...)` уже есть split на `head`/`tail`. Сейчас `tail` не используется. После check'ов существования/disabled/topo:
  - Если `referenced.getMetadata().outputs()` non-empty AND head имеет tail (т.е. `$.analysis.summary`, не голый `$.analysis`):
    - Взять первый segment `tail` (split на `.`)
    - Если его нет в `outputs[].name()` → emit `REF_UNKNOWN_FIELD`
  - Если `outputs.isEmpty()` → silently skip (warn-only spec)
- Применять и к `${X.Y}` и к `$.X.Y` формам.
- Special case self-ref (`head == referrerId`): тоже проверять — ловит typo в `verify.on_fail.inject_context`.

**Effort:** 1.5h

### Step 1.5 — Tests

**Files:**
- `workflow-core/src/test/java/com/workflow/config/PipelineConfigValidatorTest.java` (extend)
- New: `BlockMetadataOutputsTest.java`

Cases:
1. `feature.yaml` валиден post-PR1 (no new errors).
2. `condition: "$.analysis.nonsense_field"` → `REF_UNKNOWN_FIELD` warning.
3. Block без `outputs` (e.g. `shell_exec`) → no warning.
4. Self-ref bogus field → warning.
5. `pipeline-editor-roundtrip` Playwright-тесты проходят.
6. Unit: `BlockMetadata.defaultFor(...)` — non-null `outputs`, `recommendedRank=0`.
7. Unit: `FieldSchema(...)` — `required=true → level="essential"`, `required=false → level="advanced"`.

**Effort:** 1.5h

### Step 1.6 — Frontend type updates

**File:** `workflow-ui/src/types.ts`

- `FieldSchemaDto`: add `level?: 'essential' | 'advanced'`.
- `BlockMetadataDto`: add `outputs?: FieldSchemaDto[]`, `recommendedRank?: number`.

Optional поля — не ломают существующих consumer'ов.

**Effort:** 10 min

### PR-1 total: **~7 hours**

---

## PR-2: Side-panel restructure

**Pre-req:** PR-1.6 (FE types) + минимум 1-2 блока размечены в PR-1.3 (для `OutputsRefPicker` dev-data).

### Step 2.1 — New `Section` accordion primitive

**File (new):** `workflow-ui/src/components/PipelineEditor/Section.tsx`

Props: `title`, `defaultOpen`, `forceOpen` (внешний override при ошибках), `testId` (e.g. `section-essentials`), `badge` (e.g. red dot).
Внутри — controlled `open` state с `onToggle`, keyboard-accessible (`button` с `aria-expanded`).

**Effort:** 0.5h

### Step 2.2 — Extract `PinnedHeader` from current SidePanel

**File:** `workflow-ui/src/components/PipelineEditor/SidePanel.tsx`

Carve out текущие lines 51-85. Новые обязанности:
- ID rename input (preserve `data-testid="block-id-input"`).
- Compact row: `block.block` + `meta?.label` (read-only).
- `PhaseSelector` ПЕРЕЕЗЖАЕТ из `CommonFields` сюда (рядом с type/label).
- Toggles: `enabled`, `approval`.
- Кнопки: delete, close.
- Validation errors banner (под header, pinned, не в scrollable секции).

**Effort:** 1h

### Step 2.3 — Refactor `CommonFields` → `EssentialsSection`

Заменить `CommonFields`:
- `depends_on` picker (preserve `data-testid="depends-on-picker"`).
- Block-specific essentials через `BlockForm` с `levelFilter="essential"`.
- Phase REMOVED (в header).
- Condition REMOVED (в `Conditions & Retry`).

**Effort:** 0.5h

### Step 2.4 — Add `levelFilter` prop to `GenericBlockForm`

**File:** `workflow-ui/src/components/PipelineEditor/forms/GenericBlockForm.tsx`

Prop `levelFilter?: 'essential' | 'advanced' | 'all'`, default `'all'` (backwards-compat для тестов).

```ts
const filteredFields = levelFilter === 'all'
  ? fields
  : fields.filter(f => {
      const effective = f.level ?? (f.required ? 'essential' : 'advanced')
      return effective === levelFilter
    })
```

Если `filteredFields.length === 0` — render nothing, parent решает скрыть секцию.

**Effort:** 0.5h

### Step 2.5 — Rework `AgentWithToolsForm` for level split

**File:** `workflow-ui/src/components/PipelineEditor/forms/AgentWithToolsForm.tsx`

Сохранить локальный `FIELDS` (safety net), но annotate `level` per entry. Принимать `levelFilter` prop.

- Essential: `user_message`, `allowed_tools`, `working_dir`
- Advanced: `bash_allowlist`, `max_iterations`, `budget_usd_cap`, `preload_from`

**Effort:** 0.5h

### Step 2.6 — Rework `VerifyForm` (recommended alternative)

**File:** `workflow-ui/src/components/PipelineEditor/forms/VerifyForm.tsx`

**Не делать** трёхсторонний split — слишком фрагментарно. Вместо:
- `VerifyForm` рендерит `subject` + `checks` + LLM-проверку + agent overrides — всё КРОМЕ `on_fail`. Кладётся целиком в `EssentialsSection` (или внутри неё разделить на essential/advanced через `levelFilter` если время позволит).
- **Pull `OnFailEditor` в отдельный freestanding компонент** — импортируется из `ConditionsAndRetrySection`.

**Effort:** 1h

### Step 2.7 — Add `OutputsRefPicker` component

**File (new):** `workflow-ui/src/components/PipelineEditor/OutputsRefPicker.tsx`

Props:
- `value`, `onChange`
- `currentBlockId`, `config`, `byType`
- `placeholder?`, `testId?`

Behavior:
- `<input type="text">` + `<datalist>`.
- Compute available refs:
  1. Find current block в `config.pipeline`.
  2. BFS up через `depends_on` (ancestors).
  3. Filter out current block.
  4. Для каждого ancestor: lookup `byType[ancestor.block]?.metadata?.outputs`, push `$.{ancestor.id}.{output.name}`.
- **Loopback edges (`verify.on_fail.target`, `on_failure.target`) НЕ следуем** — только `depends_on`.
- Datalist suggestions, free text разрешён.

**Effort:** 1.5h

### Step 2.8 — `ConditionsAndRetrySection`

**Inside:** `SidePanel.tsx`

- `condition` field — всегда. Через `OutputsRefPicker`. Preserve `data-testid="block-condition"`.
- `verify.on_fail.*` — только если `block.block === 'verify' || block.block === 'agent_verify'`. Reuses `OnFailEditor` из step 2.6.
- `on_failure.*` — только для `gitlab_ci` / `github_actions`. Hardcoded form: `action`, `target`, `max_iterations`, `failed_statuses`, `inject_context`.

**Effort:** 1.5h

### Step 2.9 — `AdvancedSection`

**Inside:** `SidePanel.tsx`

- Block-specific advanced через `BlockForm` с `levelFilter="advanced"`.
- `AgentOverrides` (existing) переезжает сюда.
- `RawJsonFallback` если `meta?.configFields?.length === 0 && meta?.outputs?.length === 0`.
- Если `AdvancedSection` пустая — не рендерить (или показать "No advanced settings").

**Effort:** 0.5h

### Step 2.10 — Auto-expand on error

**File:** `SidePanel.tsx`

Per-section `hasError` через `pathToSection(path, blockMetadata)` helper:
- Essentials: `.depends_on`, `.config.<essential field>`
- Conditions & Retry: `.condition`, `.verify.on_fail`, `.on_failure`
- Advanced: всё остальное

`forceOpen={hasError}` → `<Section>`.

Dev-fallback: `console.warn` для unmapped paths.

**Effort:** 1h

### Step 2.11 — Wire `OutputsRefPicker` into `inject_context` editors

**Files:** `forms/VerifyForm.tsx` (OnFailEditor) + `on_failure` editor.

В `inject_context` value-полях и `condition` — заменить text input на `OutputsRefPicker`.

**Optional (defer):** `interpolatable: true` flag в FieldSchema для prompt-полей. Брифом разрешено отложить.

**Effort:** 0.5h (без `interpolatable`); 1h с ним.

### Step 2.12 — Update Playwright tests

**Files:** `workflow-ui/tests/ui/pipeline-editor.spec.ts`, `pipeline-editor-roundtrip.spec.ts`

Существующие testids ОСТАЮТСЯ: `side-panel`, `block-id-input`, `block-enabled`, `block-approval`, `block-condition`, `depends-on-picker`, `block-delete`.

Новые тесты:
1. Open block — `section-essentials` open by default, остальные collapsed.
2. Validation error в advanced — `section-advanced` auto-expands.
3. `outputs-ref-picker-condition` показывает `$.X.Y` options для блока с depends_on.
4. Verify-block — `section-conditions-retry` содержит on_fail target selector.

**Effort:** 1h

### Step 2.13 — Final testid audit

Verify все existing testids живут после рефактора. Новые: `section-essentials`, `section-conditions-retry`, `section-advanced`, `outputs-ref-picker-condition`, `outputs-ref-picker-feedback`.

**Effort:** 0.5h

### PR-2 total: **~10 hours**

---

## PR-3: Creation Wizard

**Pre-req:** PR-1 + PR-2 в `main` (закоммичены — `c567f03` + `6f4df4c`).

### Findings (отличия от изначального дизайна)

При чтении кода обнаружено:

1. **`POST /api/pipelines/validate` принимает только `configPath`** (`RunController.java:895-904`), не body. Wizard'у нужно валидировать pipeline ДО записи на диск — нужен новый endpoint `POST /api/pipelines/validate-body`.
2. **`POST /api/pipelines/new`** клонирует встроенную `feature.yaml` с заменой `name`/`description`. Для wizard'а нужен body-mode: расширить эндпоинт чтобы принимал готовый `PipelineConfig`. Альтернатива (отдельный `/new-from-config`) даёт API surface bloat.
3. **3 блока с `recommendedRank=100`** в IMPLEMENT (`code_generation`, `agent_with_tools`; `analysis` фактически в ANALYZE). Tie-breaker нужен — hardcoded preference per phase: IMPLEMENT → `agent_with_tools` first, `code_generation` fallback. PUBLISH → `github_pr`/`gitlab_mr` равнозначны (radio-выбор без preselect для v1).
4. **Vitest нет в `workflow-ui`** — только Playwright. v1 решение: unit-тесты `recommendedFor` через Playwright-component harness, не добавлять Vitest как dep.
5. **`OrchestratorBlock.phase = ANY`, `rank=70`** — wizard итерирует по фазам, ANY-блоки не surfaces. Acceptable: orchestrator — power-user feature, доступен через палитру post-create.
6. **`Canvas.tsx` уже принимает controlled props** — для read-only Preview можно reuse с no-op handlers.
7. **`computeAvailableRefs`** (OutputsRefPicker) — pure function, reuse в wizard если нужно.

### Архитектурные решения

- **State management:** `useReducer` внутри custom hook `useCreationWizard` (по аналогии с `usePipelineEditor`). Action surface: `SET_NAME`, `GOTO_STEP`, `NEXT/PREV`, `SKIP_PHASE`, `REPLACE_BLOCK`, `ADD_BLOCK`, `PATCH_BLOCK_CONFIG`, `REMOVE_BLOCK`, `SET_RETRY`, `SET_VALIDATION`.
- **Side-rail:** vertical stepper (Tailwind-кастомный, без MUI/shadcn). 8 items: 6 phases + Retry policy (условный) + Preview. Color stripes из `phaseColors.ts`. Click → back-navigation на посещённые фазы.
- **Draft-save:** ephemeral до Create (никакой persistence). Mitigation: `beforeunload` guard когда state.dirty.
- **`recommendedFor(phase, registry, chosenSoFar)`:** pure function с алгоритмом max(rank) → tie-break по hardcoded `PREFERRED[phase]` → alphabetical fallback.

### Backend steps

#### Step 3.1 — `POST /api/pipelines/validate-body`

**File:** `workflow-core/src/main/java/com/workflow/api/RunController.java`

Новый endpoint, принимающий `@RequestBody PipelineConfig`. Возвращает тот же `ValidationResult` envelope. Authorization: `OPERATOR | RELEASE_MANAGER | ADMIN`.

**Effort:** 0.5h.

#### Step 3.2 — Extend `POST /api/pipelines/new` body-mode

**File:** `workflow-core/src/main/java/com/workflow/api/RunController.java:813-886`

Если в request body есть `pipeline: List<BlockConfig>` — использовать переданный config вместо клонирования template. Backwards-compat: empty/standard templates по-прежнему работают.

**Effort:** 0.5h.

### Frontend types/services

#### Step 3.3 — `api.ts` wrappers

**File:** `workflow-ui/src/services/api.ts`

- New `api.validatePipelineBody(config)` → POST `/pipelines/validate-body`.
- Extend `api.createPipeline()` body type — accept optional `pipeline?: BlockConfigDto[]`, `entry_points?`.

**Effort:** 10 min.

### Frontend wizard core

#### Step 3.4 — `useCreationWizard` hook

**File (new):** `workflow-ui/src/hooks/useCreationWizard.ts`

State: `currentStep`, `phases: Record<Phase, PhaseState>`, `retryEnabled`, `slug/displayName/description`, `validationErrors`, `validating`. Reducer + computed (recommendedFor, previewConfig, isStepValid, canCreate, showRetryStep). `previewConfig` builder: ID auto-numbering для дубликатов типов, depends_on chain через не-skipped фазы, retry edges (`verify.on_fail` для VERIFY, `on_failure` для CI).

**Effort:** 3h.

#### Step 3.5 — `recommendedFor` utility

**File (new):** `workflow-ui/src/components/PipelineEditor/Wizard/recommendedFor.ts`

Pure function (см. design выше). Hardcoded `PREFERRED` map per phase.

**Effort:** 1h (включая 8-10 unit-тестов через Playwright-component).

#### Step 3.6 — `WizardSideRail`

**File (new):** `workflow-ui/src/components/PipelineEditor/Wizard/WizardSideRail.tsx`

Vertical stepper. Color stripe + check/dot icon + status. Back-navigation для visited фаз.

**Effort:** 1h.

#### Step 3.7 — `WizardPhaseStep`

**File (new):** `workflow-ui/src/components/PipelineEditor/Wizard/WizardPhaseStep.tsx`

Самый плотный компонент. Per-phase render: header + N `BlockSlotEditor` (block type + Replace dropdown + delete + form-essentials через `BlockForm` с `levelFilter="essential"`). Buttons: `[Replace ▾] [+ Add another] [Skip phase] [← Назад] [Далее →]`.

**Effort:** 2.5h.

#### Step 3.8 — Extract `BlockForm`

**File (new):** `workflow-ui/src/components/PipelineEditor/forms/BlockForm.tsx` (extract из `SidePanel.tsx:643-684`)

Pure component: switch on `block.block` → AgentWithToolsForm | VerifyForm | GenericBlockForm | RawJsonFallback. Принимает `levelFilter` prop. `SidePanel.tsx` обновляется на импорт.

**Effort:** 0.5h.

#### Step 3.9 — `WizardRetryStep`

**File (new):** `workflow-ui/src/components/PipelineEditor/Wizard/WizardRetryStep.tsx`

Single-checkbox UI: «Retry on failure (max 2 attempts)» + hint paragraph + diagram (verify→loopback→impl, ci→loopback→impl). Visibility: только если `showRetryStep === true` (есть verify-блок ИЛИ CI-блок).

**Effort:** 0.5h.

#### Step 3.10 — `WizardPreviewStep`

**File (new):** `workflow-ui/src/components/PipelineEditor/Wizard/WizardPreviewStep.tsx`

Read-only `<Canvas>` с no-op handlers + validation banner + IMPLEMENT-block sanity check (Create disabled если нет ни одного IMPLEMENT). Авто-валидация через `api.validatePipelineBody(previewConfig)`.

**Effort:** 1h.

#### Step 3.11 — `CreationWizard` root

**File (new):** `workflow-ui/src/components/PipelineEditor/Wizard/CreationWizard.tsx`

Header + Side-rail (180px) + content (current step renderer). Pre-step: slug/displayName/description (reuse поля из `NewPipelineModal`). Auto-validate debounced 300ms. Create handler: `api.createPipeline({slug, displayName, description, pipeline, entry_points})` → onCreated.

**Effort:** 2h.

#### Step 3.12 — Wire wizard в `NewPipelineModal`

**File:** `workflow-ui/src/components/PipelineEditor/index.tsx:381-501`

Снять `disabled` с радио `custom`. При выборе `custom` + Submit → закрыть `NewPipelineModal`, открыть `<CreationWizardModal>` (full-screen modal, larger viewport).

**Effort:** 0.5h.

### Tests

#### Step 3.13 — Unit-тесты `recommendedFor`

**File (new):** Playwright-component spec (`tests/ui/wizard-recommended-for.spec.ts`).

8 кейсов: empty registry, single-block-in-phase, ties, hardcoded preference, alphabetical fallback.

**Effort:** 0.5h (через Playwright; +0.5h если Vitest).

#### Step 3.14 — Playwright wizard E2E

**File (new):** `workflow-ui/tests/ui/pipeline-editor-wizard.spec.ts`

10 кейсов: open wizard, side-rail, recommended preset auto-fill, Replace dropdown, Add another, Skip phase, Retry conditional appearance, retry edges visible in preview, missing-impl validation, successful Create.

**Effort:** 2h.

#### Step 3.15 — Backend IT

**Files:**
- `workflow-core/src/test/java/com/workflow/api/PipelineValidateBodyIT.java` (new)
- Extend `RunControllerIT` для `/pipelines/new` body-mode.

4 кейса: validate-body valid/invalid, /new with custom body / with invalid body.

**Effort:** 1h.

### Step 3.16 — Update plan doc (этот файл)

Уже сделано как часть запуска PR-3.

**Effort:** 0.5h (включено в этот edit).

### Dependencies

```
3.1 (validate-body) ─┐
3.2 (/new body-mode)─┤  Backend independent.
3.3 (api.ts)         ─┴─► 3.4 (useCreationWizard hook)
                          │
3.5 (recommendedFor) ────►├── 3.7 (WizardPhaseStep)
3.8 (extract BlockForm) ►├── 3.7
3.6 (SideRail)       ────►├── 3.11 (CreationWizard)
3.9 (RetryStep)      ────►├── 3.11
3.10 (PreviewStep)   ────►├── 3.11
                          └─► 3.12 (wire)
                              ├─► 3.13 (unit recommendedFor)
                              ├─► 3.14 (Playwright E2E)
                              └─► 3.15 (Backend IT)
```

**Critical path:** 3.1 → 3.4 → 3.7 → 3.11 → 3.12 → 3.14.

### Risks and trade-offs

1. **`validate-body` дублирует существующий `validate`** (разный input). Mitigation: javadoc-link, deprecate older позже.
2. **`POST /pipelines/new` две модели** (template-clone / body-passthrough) — slight smell. v1 acceptable.
3. **Hardcoded PREFERRED list out-of-sync** при добавлении нового блока с rank=100. Mitigation: comment + alphabetical fallback. Долгосрочно: добавить `recommendedTier: 'primary'|'alternate'` в `BlockMetadata`.
4. **Preview validation network cost.** Debounced 300ms + AbortController для in-flight cancel. Cheap (no LLM, structural).
5. **F5 теряет state.** `beforeunload` guard.
6. **OrchestratorBlock не recommended.** Power-user goes via palette.
7. **PUBLISH github vs gitlab tie.** v1 radio. v2 — auto-select по `config.integrations`.
8. **`condition` / `inject_context` не редактируются в wizard'е** (только Essentials). Acceptable per grill — Advanced настройки через side-panel post-create.
9. **`writeFull` env-expansion mismatch с validate-body.** Mitigation: rely on writer rejection (400 + errors).
10. **`agent.tier`/`agent.model` не в wizard'е.** Block runtime defaults покрывают (как в `feature.yaml`).

### Test strategy

**Unit:** `recommendedFor` (rank ties, empty phase, preference, alphabetical), `previewConfig` builder (depends_on chain, retry edge generation, ID auto-numbering), `useCreationWizard` reducer (8-10 кейсов на actions).

**Playwright:** `pipeline-editor-wizard.spec.ts` (10 кейсов выше) + sanity-проверка на `pipeline-editor.spec.ts` (NewPipelineModal preserved).

**Backend Java:** `PipelineValidateBodyIT`, `RunControllerCreatePipelineWithBodyIT`.

**Manual:** full-flow + skip-everything-except-IMPLEMENT + Retry on/off + F5 confirmation + failed validation banner.

### PR-3 total: **~17.5 hours + 25% buffer ≈ 22h**

---

## PR-4: Documentation

### Step 4.1 — `docs/adding-a-block.md`

Полный onboarding-гайд для добавления нового block type. Содержание:
- Минимальный пример (`HelloWorldBlock`) за 5 минут.
- `Block` interface contract.
- `BlockMetadata` 8-arg canonical constructor (детально каждое поле).
- `FieldSchema` фабрики + level system (essential/advanced).
- `outputs` декларация → OutputsRefPicker + Level 3 validation.
- `Phase.BLOCK_TYPE_PHASES` dual-source caveat.
- Что появляется в UI автоматически.
- Validator контракт.
- Тестирование (unit + `*IT` для external calls).
- Чек-лист «новый блок готов».
- Reference-блоки для копирования.
- Anti-patterns (что НЕ делать).

**Effort:** ~30 min.

**Status:** ✅ Закоммичено в `5a5971c`.

---

## Dependencies

```
PR-1:
  1.1 (FieldSchema.level)        ──┐
  1.2 (BlockMetadata.outputs)    ──┼─→ 1.3 (10 blocks) ─→ 1.4 (validator) ─→ 1.5 (tests)
  1.6 (frontend types)           ──┘
                                              (1.6 параллельно с 1.3-1.5)

PR-2 ждёт PR-1.6 + ≥1 размеченный блок:
  → 2.1 (Section primitive)
  → 2.2 (PinnedHeader) ─┐
  → 2.3 (Essentials)    ├─ параллельно
  → 2.4 (level filter)  │
  → 2.5 (AgentForm)     │
  → 2.6 (VerifyForm + OnFailEditor)  ─→ нужно для 2.8
  → 2.7 (OutputsRefPicker)            ─→ нужно для 2.8, 2.11
  → 2.8 (ConditionsAndRetry)
  → 2.9 (Advanced)
  → 2.10 (auto-expand)               ─ нужны 2.8, 2.9
  → 2.11 (wire picker)
  → 2.12 (Playwright)
  → 2.13 (testid audit)
```

---

## Risks and trade-offs

1. **`FieldSchema` record breaking change.** Добавление component меняет JSON shape (новый key). FE TS interfaces — open, ок. Java pattern-matching destructuring — нужен grep по codebase, в проекте не наблюдается.
2. **`BlockMetadata` 5-arg compat ctor** — keeping intact via delegation, риск только в record-component-positional-access (не наблюдается).
3. **OrchestratorBlock outputs union** — false-positives на `$.review_block.goal` ок для PR-1, fix в PR-3.
4. **Datalist limitations** — не cursor-aware. Playwright тесты через `input.fill()`, не визуальный dropdown. Trade-off: simple v1, заменить на custom popover если UX потребует.
5. **Auto-expand correctness** — новый field type без обновления `pathToSection` → не auto-expand'нется. Mitigation: `console.warn` fallback в dev.
6. **`GenericBlockForm.levelFilter` default = 'all'** — preserves test compat, но defeats purpose если refactor случайно дропнет prop. Mitigation: explicit prop в каждом call site, comment.
7. **Loopback `depends_on`** — спецификацией указано не следовать, walker идёт только `depends_on`. No risk.
8. **Phase relocation в header** — может уплотнить. Mitigation: 2-row stack если надо.
9. **VerifyForm split** — recommended alternative (только OnFailEditor extract) уменьшает дублирование state.
10. **Validator nested fields** — `$.X.Y.Z` валидируется только до `Y`. `Z` без nested-схем не проверяем. Acceptable PR-1.

---

## Test strategy

**Unit (Java, JUnit 5):**
- `FieldSchema` level-default-from-required.
- `BlockMetadata` 5/6/8-arg ctor compat.
- `PipelineConfigValidator` REF_UNKNOWN_FIELD (acceptance criteria 2+3 PR-1).
- 10 блоков `getMetadata()` non-null `outputs`.

**Integration (Java, `*IT`):**
- `FeaturePipelineConfigTest` — feature.yaml valid post-PR1.

**Playwright (UI, `tests/ui/`):**
- Existing `pipeline-editor.spec.ts` — testid invariants.
- New: section structure, auto-expand, OutputsRefPicker, verify on_fail editor.

**Manual:**
- Click через каждый block type → side-panel renders без crash.
- Edit essentials → save → reload → state preserved (existing roundtrip-тест).
- `$.` в condition → autocomplete suggestions.

---

## Effort summary

| PR | Status | Steps | Estimate |
|---|---|---|---|
| PR-1 | ✅ `c567f03` | Types (1.1-1.2), 10 blocks (1.3), validator (1.4), tests (1.5), FE types (1.6) | **~7h** |
| PR-2 | ✅ `6f4df4c` | Section primitive, refactor (2.1-2.3), level filter (2.4-2.6), OutputsRefPicker (2.7), sections (2.8-2.9), auto-expand (2.10), wiring (2.11), tests (2.12-2.13) | **~10h** |
| PR-3 | ⏳ planned | Backend (3.1-3.2), api.ts (3.3), wizard hook (3.4-3.5), wizard UI (3.6-3.11), wire (3.12), tests (3.13-3.15) | **~17.5h** |
| PR-4 | ✅ `5a5971c` | `docs/adding-a-block.md` (4.1) | **~0.5h** |
| **Total** | | | **~35h + 25% buffer ≈ 44h** |

---

## Critical files

**PR-1 + PR-2 (готово в main):**
- `workflow-core/src/main/java/com/workflow/blocks/FieldSchema.java`
- `workflow-core/src/main/java/com/workflow/blocks/BlockMetadata.java`
- `workflow-core/src/main/java/com/workflow/config/PipelineConfigValidator.java`
- `workflow-ui/src/components/PipelineEditor/SidePanel.tsx`
- `workflow-ui/src/components/PipelineEditor/forms/GenericBlockForm.tsx`
- `workflow-ui/src/types.ts`

**PR-3 (планируется):**
- `workflow-ui/src/hooks/useCreationWizard.ts` (NEW — wizard state machine + previewConfig builder)
- `workflow-ui/src/components/PipelineEditor/Wizard/CreationWizard.tsx` (NEW — root component)
- `workflow-ui/src/components/PipelineEditor/Wizard/WizardPhaseStep.tsx` (NEW — densest UI; renders block slots + Replace/AddAnother/Skip)
- `workflow-ui/src/components/PipelineEditor/Wizard/recommendedFor.ts` (NEW — preset selection algorithm)
- `workflow-ui/src/components/PipelineEditor/forms/BlockForm.tsx` (NEW — extract из SidePanel для reuse в wizard'е)
- `workflow-core/src/main/java/com/workflow/api/RunController.java` (extend `/pipelines/new` + add `/pipelines/validate-body`)
- `workflow-ui/src/services/api.ts` (NEW `validatePipelineBody`, extend `createPipeline` body)
- `workflow-ui/src/components/PipelineEditor/index.tsx` (mount `<CreationWizard>` modal)

# SDLC Quality Pipeline — Implementation Plan

Согласован grill-сессией 2026-05-14. Решения зафиксированы в `memory/plan_sdlc_quality.md`.
Этот документ — canonical design + roadmap для 9 связанных изменений в pipeline-платформе.

## TL;DR

**Проблема.** Текущий full-flow pipeline (`config/pipeline.full-flow.yaml`, 19 блоков) имеет три структурные дыры:
1. Стартует на любом baseline — даже если `main` уже красный, что обесценивает все последующие verify.
2. `verify_code → codegen` loopback циклится на локальных моделях (vLLM Qwen3-4B / Ollama 8B) и упирается в `max_iterations` без fallback к более сильной модели.
3. Нет явной роли «tester который думает что тестировать» и «tech lead который ревьюит run целиком», размытие ответственности по `verify` / `ai_review` / `orchestrator`.

**Решение.** Эволюция graph'а на 9 изменений, разложенных на 4 фазы по убыванию ROI / по разблокировке зависимостей:

- **Phase A** (закрываем основную боль): preflight gate + escalation ladder local→cloud→human.
- **Phase B** (фундамент для skip-логики): clarity buckets + cost tracking.
- **Phase C** (расширение ролей): context_scan + per-block agent_verify + test_planning.
- **Phase D** (финиш и отчётность): visual_test_gen + tech_lead_gate + run_report.

**Принцип.** Деминговский PDCA на каждой stage: явный criterion + Check (agent_verify per block) + Act (loopback или escalate). Tech lead — это **stage gate перед MR**, не watcher над всем.

---

## Roadmap

### Phase A — Closing the bleeding (independent, parallel)

**A1. Escalation ladder + no-progress detect.**
Новое поле `verify.on_fail.escalation: [...]` (или `on_failure.escalation: [...]`). Ladder local→cloud-tier→human. Soft trigger «no-progress» (issues итерации N ⊇ issues N-1 на ≥80%) запускает escalation немедленно, не дожидаясь `max_iterations`. Failure context bundle с hint'ом передаётся в cloud-tier модель.
- **Deliverables:** `EscalationResolver`, `EscalationStep` (sealed), новое поле в `OnFailConfig` / `OnFailureConfig`, no-progress detector в `PipelineRunner.runWithRetry`, bundle composer, обновление `application.yaml`, badge «escalated» в UI run-detail.
- **Unblocks:** ничего из последующих фаз. Можно катить independent.

**A2. `preflight` block + baseline cache.**
Новый блок в самом начале графа. Auto-detect команд из манифестов + override через target-repo `CLAUDE.md` (новая секция `## Preflight`). Scope = весь проект. Кэш по `(projectId, mainCommitSha, sha256(preflightConfig))`, TTL 7 дней, ls-remote проверка при age > 1h. Default `on_red: block` с эскалацией оператору.
- **Deliverables:** `PreflightBlock` в `blocks/`, `PreflightSnapshot` JPA entity, `PreflightConfigResolver` (auto-detect + CLAUDE.md), `PreflightCacheService`, миграция БД, `application.yaml` `workflow.preflight-cache-ttl: PT168H`, API `POST /api/projects/{id}/preflight/refresh`.
- **Unblocks:** ничего. Independent от A1.

### Phase B — Foundation for skip logic

**B1. `intake_assessment` block + clarity buckets.**
Лёгкий блок на cheap-tier модели (`gpt-4o-mini` / vLLM-flash) между `intake` и `analysis`. Считает `clarity_pct` + `recommended_path: clarify | full | fast`. Три discrete bucket'а:
- `clarify` (<60%) — forced clarification.
- `full` (60-85%) — обычный pipeline.
- `fast` (>85%) — skip clarification + tasks-decomposition + test_gen-mandatory + tech_lead_gate. Preflight + verify + ai_review + CI остаются.
- **Deliverables:** `IntakeAssessmentBlock`, обновление `pipeline.full-flow.yaml` (новый блок + `condition:` на skippable блоках), обновление `BlockMetadata.outputs`.
- **Unblocks:** B2 не зависит; C1/C2/C3 могут использовать `path == 'fast'` для skip-логики.

**B2. Cost tracking.**
`LlmCall.costUsd` (computed), `PipelineRun.totalCostUsd` (aggregate), `Models.pricing` static map (USD/1K tokens per model, обновляется руками 1×/квартал). UI отображает running total в run-detail.
- **Deliverables:** миграция `LlmCall` + `PipelineRun`, `PricingTable` в `llm/`, обновление `LlmClient` (заполнение costUsd на каждый round-trip), компонент `RunCostBadge` в UI.
- **Unblocks:** budget-guard для A1 (без B2 эскалация без cap'а на стоимость).

### Phase C — Role expansion

**C1. `context_scan` block + best-practices markdown registry.**
Новый блок после `analysis`, до `tasks`/`test_planning`/`codegen`. Под капотом `agent_with_tools` loop (Read/Glob/Grep), flash-tier модель, 5-10 итераций. Output: `tech_stack`, `code_conventions`, `applicable_best_practices`, `suggestions_for_codegen`. Кэш по `(project, main_sha, language)`.
Static markdown registry: `workflow-core/src/main/resources/knowledge/best-practices/{java,python,typescript,go,rust}.md`. Java — стартовый набор: Records для DTO, Lombok-allow-list, MapStruct, `@Transactional(readOnly)`, AssertJ over Hamcrest, Java 21 pattern-matching. Override через target-repo `CLAUDE.md` секция `## Best Practices`.
Skip на `fast`-path.
- **Deliverables:** `ContextScanBlock`, `BestPracticesRegistry` reader, `java.md` стартовый, `ContextSnapshot` JPA entity (cache).

**C2. Standardize `agent_verify` per major block.**
Не новый блок — rollout existing `agent_verify` в `pipeline.full-flow.yaml` после analysis, context_scan, test_planning, test_generation, codegen. Per-block acceptance criterion (block-specific). Loopback policy: max 2 iter → escalate (A1).
- **Deliverables:** только YAML-изменения + per-block criterion-промпты.

**C3. `test_planning` + расширенный `test_generation` schema.**
Новый блок `test_planning` (smart-tier, thinking). Output: `strategy: tdd | adaptive | none`, `cases: [{type, target, scenario, boundary_origin, priority}]`, `coverage_estimate`. BVA + equivalence partitioning в promtp'е через явное поле `boundary_origin`.
TDD-strict mode: при `strategy: tdd` добавляется вспомогательный блок `test_must_fail` между `test_generation` и `codegen`, проверяет что новые тесты красные. После codegen — должны быть зелёные.
- **Deliverables:** `TestPlanningBlock`, `TestMustFailBlock`, расширение `TestGenerationBlock.run` (читает `test_plan`, генерит per-case), обновление `agent_verify` для test coverage.

### Phase D — Finish & reporting

**D1. `visual_test_generation` block.**
Отложен до первого UI-target. Когда появится: новый блок после `codegen`, генерит Playwright/Cypress на основе `test_plan.cases[type='visual']`. Skip через `condition: "$.context_scan.tech_stack.has_ui == true"`. Запускается в `acceptance` (deployed env).
- **Deliverables:** `VisualTestGenerationBlock`, integration с Playwright fixtures (через `Bash` tool в `agent_with_tools`).

**D2. `tech_lead_gate` block.**
Реализация = `OrchestratorBlock` с новым `mode: run_review` над всеми outputs. Ревьюит **весь run** против `analysis.acceptance_checklist`. Skip на `fast`-path через `condition`.
- **Deliverables:** новый mode в `OrchestratorBlock` или wrapper-блок `TechLeadGateBlock`, обновление графа.

**D3. `run_report` aggregator.**
Финальный блок в самом конце, agregator-only, never blocking. Compose из существующих outputs: acceptance_checklist ✓/✗, test_coverage, ci, deploy, flags. Отправка в notification channels, persist как `RunReport` entity.
- **Deliverables:** `RunReportBlock`, `RunReport` entity, `RunReportComposer`, UI компонент `RunReportPanel`.

---

## Design decisions log

Каждое решение — short statement + почему. Audit trail grill-сессии для future-self.

### Q1: Preflight закрывает «main красный до нас» (А), Escalation закрывает «codegen ломает зелёные» (B)
Это две разные боли, два разных механизма. A → preflight gate. B → escalation ladder. Оба независимы, можно катить параллельно.

### Q2: Preflight default = `block` на красном baseline, Hybrid per-project policy
Жёсткий gate с эскалацией — самый честный сигнал. Auto-fix branch как side-effect feature-pipeline'а опасен (lazy scope creep). Soft baseline хрупкий (тесты гуляют под `@Disabled`/rename). Per-project override через `preflight.on_red: block | warn | autofix` оставляем для legacy-клиентов с known-broken тестами.

### Q3: Preflight config из target-repo `CLAUDE.md` + auto-detect fallback, scope = full, SHA-cache
Конфигурация preflight живёт там же, где CLAUDE.md (target-repo) — paralleling существующий паттерн `ProjectClaudeMd`. Auto-detect по manifest'ам (gradle/maven/npm/pytest) покрывает 80% без конфига. Scope = весь проект, потому что preflight идёт ДО analysis (нет ещё affected_components). Кэш делает full-scope дешёвым.

### Q4: Отдельный `intake_assessment` блок (cheap-tier) + discrete buckets (clarify/full/fast)
Внутри analysis нельзя — analysis уже потрачен на smart-tier, экономии нет. Continuous % через `condition:` plохо — разные блоки выберут разные пороги, семантика «73%» размывается. Discrete buckets — три понятных режима, оператор и читатель кода имеют ментальную модель.

### Q5: Отдельный `context_scan` блок (flash-tier) + static markdown best-practices + CLAUDE.md override
Inline в analysis — раздувает роль analysis, как с раздутым OrchestratorBlock'ом. Context scan — это **исполнительская работа** (читать манифесты, грепать), не аналитическая → flash-tier, не smart. Best practices как markdown в репо — code review через PR. DB-entity overhead не оправдан (best practices меняются 1×/квартал).

### Q6: `test_planning` + `test_generation` отдельно, `visual_test_generation` отдельно, adaptive TDD, BVA через `boundary_origin` schema
Разделение мышления (что тестировать, BVA) и исполнения (написать на JUnit/pytest). Visual отдельно — другая инфраструктура (Playwright/headless), другой жизненный цикл (после codegen, не до). Adaptive TDD — strict TDD ломается на refactor/rename. `boundary_origin` обязывает модель думать в терминах границ.

### Q7: Per-block `agent_verify` standardization + холистический `tech_lead_gate` перед MR + `run_report` aggregator
Universal supervisor-agent — мутная семантика, дублирует verify. `tech_lead_gate` — completeness-focused («всё ли из analysis сделано»), не code-focused (это уже `ai_review`). `acceptance_checklist_run` с LLM-генерируемыми live-проверками отвергнут: цена реализации огромная, accuracy непредсказуемая на сложных criteria. `run_report` — только compose из существующих сигналов, never blocking.

### Q8: Soft trigger (no-progress detect) + Hard trigger (max_iter) → Ladder local→cloud→human, full bundle + hint, per-run USD budget
Self-declared «stuck:true» — модели плохо калибруют uncertainty. Per-cost budget — это про экономику, не про качество. Ladder обязателен sequential: local не справился → cloud (glm-4.6 / claude-sonnet-4-6) → human. Minimum bundle недостаточен — cloud повторит ошибки. USD-only budget — `cloud_max_calls` дополнительно запутывает.

### Q9: Phase order A → B → C → D (closing bleeding first, then foundation, then expansion, then finish)
Альтернативы отвергнуты: «всё фундаментальное B сначала» — операционно теряем ROI до Phase C; «test/visual сначала» — реальная боль не там; «параллельно всё» — потеряем фокус. A1 (escalation) и A2 (preflight) independent — можно делать параллельно разными PR.

### Q10: Opt-out escalation (default ON), global `application.yaml` + Project entity override, USD-only budget, 3-level resolver
Opt-in заставляет переписать кучу YAML-блоков. Opt-out с явным `escalation: none` для блоков где эскалация бессмысленна (mr, vcs_merge, preflight). 3-level resolver block → Project → global, аналогично `ModelPresetResolver`. Budget per-project (`Project.cloudBudgetUsd` default $5) + pipeline override + hardcoded $10 hard cap.

### Q11: Cache key `(projectId, mainCommitSha, sha256(preflightConfig))`, TTL 7 дней, ls-remote только если age > 1h, manual API refresh
Build-tool versions в key — overkill (требует `java -version` runtime вызов). TTL 7д — компромисс «дрейф deps registries» vs «не палим preflight time каждый день». Verify HEAD только при age > 1h — экономим 200-500ms на «закоммитил и сразу запустил» сценариях. Manual refresh API обязателен; UI/CLI отложены до Phase B.

---

## Block specs

### `preflight` (new, Phase A2)

```yaml
- id: preflight
  block: preflight
  depends_on: []      # самый первый блок
  approval_mode: auto
  config:
    on_red: block | warn | autofix     # default: block
  # commands читаются из <workingDir>/CLAUDE.md secтion `## Preflight`
  # auto-detect fallback: pom.xml→mvn, build.gradle→gradle, package.json→npm test, pyproject.toml→pytest
```

**Output schema:**
```yaml
preflight:
  status: passed | red_blocked | warning
  build_status: ok | failed | skipped
  test_status: ok | failed | skipped
  baseline_failures:        # list of FQNs of pre-existing failures
    - "com.example.FooTest.testBar"
  baseline_hash: "<sha256>"
  cached: true | false      # was this a cache hit
  cache_source_sha: "<main commit sha>"
  duration_ms: 12345
```

**Поведение:**
- На `on_red: block` + `baseline_failures.size > 0` → status = `red_blocked` → pipeline pauses с approval gate «main красный, разруливай».
- На `on_red: warn` → status = `warning`, pipeline продолжает; verify_code и ci потом сравнивают свои failures с baseline_failures по FQN.
- На `on_red: autofix` → switch к отдельному pipeline `config/fix-broken-baseline.yaml` (NOT auto-built в feature-pipeline).

### `intake_assessment` (new, Phase B1)

```yaml
- id: intake_assessment
  block: intake_assessment
  depends_on: [intake]
  agent:
    model: fast      # cheap-tier preset
    temperature: 0.3
```

**Output:**
```yaml
intake_assessment:
  clarity_pct: 0..100
  clarity_breakdown:
    - { criterion: "acceptance_criteria_explicit", passed: true|false, evidence: "..." }
    - { criterion: "scope_clear", passed: true|false, ... }
    - { criterion: "edge_cases_listed", passed: true|false, ... }
    - { criterion: "dod_measurable", passed: true|false, ... }
    - { criterion: "perf_security_considered", passed: true|false, ... }
  recommended_path: clarify | full | fast
  rationale: "Краткое объяснение почему именно этот path."
```

### `context_scan` (new, Phase C1)

```yaml
- id: context_scan
  block: context_scan
  depends_on: [analysis]
  condition: "$.intake_assessment.recommended_path != 'fast'"
  agent:
    model: flash
  tools:
    bash_allowlist: []      # без bash для скана
    max_iterations: 10
```

**Output:**
```yaml
context_scan:
  tech_stack:
    language: java | python | typescript | ...
    framework: spring-boot | django | react | ...
    build_tool: gradle | maven | npm | ...
    key_deps: [lombok, mapstruct, jpa, junit5, ...]
  code_conventions:
    - "uses Lombok @Data for entities (sampled UserEntity.java)"
    - "constructor injection via @RequiredArgsConstructor"
  applicable_best_practices:
    - { rule: "Use Records for DTOs", source: "java.md", confidence: 0.9 }
  suggestions_for_codegen:
    - "При добавлении нового entity, следуй Lombok-паттерну из X.java"
  cached: true | false
```

### `test_planning` (new, Phase C3)

```yaml
- id: test_planning
  block: test_planning
  depends_on: [analysis, context_scan]
  condition: "$.intake_assessment.recommended_path != 'fast'"
  agent:
    model: smart
```

**Output:**
```yaml
test_plan:
  strategy: tdd | adaptive | none
  cases:
    - id: tc-1
      type: unit | integration | e2e | visual
      target: "UserService.register"
      scenario: "valid email + password"
      boundary_origin: null
      priority: critical | important | nice_to_have
    - id: tc-2
      type: unit
      target: "UserService.register"
      scenario: "email exactly 254 chars (RFC 5321 boundary)"
      boundary_origin: "email.length=254"
      priority: critical
  coverage_estimate: 0.0..1.0
  notes: "..."
```

### `test_must_fail` (new, Phase C3, conditional)

```yaml
- id: test_must_fail
  block: test_must_fail
  depends_on: [test_generation]
  condition: "$.test_plan.strategy == 'tdd'"
```

Запускает свежесгенерированные тесты, ожидает что они **красные**. Если зелёные — codegen не нужен / поведение уже реализовано → pipeline продолжает с warning (или фейлится по policy).

### `tech_lead_gate` (new, Phase D2)

Реализация = `OrchestratorBlock` с новым `mode: run_review`. Subject = `run` (вся история), не блок.

```yaml
- id: tech_lead_gate
  block: orchestrator
  depends_on: [verify_code, ai_review, test_generation]
  condition: "$.intake_assessment.recommended_path != 'fast'"
  config:
    mode: run_review
    context_blocks: [analysis, test_plan, codegen, verify_code, ai_review, context_scan]
  agent:
    model: smart
```

**Output:** `checklist_status` mapping `analysis.acceptance_checklist` items → ✓/✗ with evidence, regressions list, action (proceed | loopback | escalate).

### `run_report` (new, Phase D3)

```yaml
- id: run_report
  block: run_report
  depends_on: [verify_prod]
  approval_mode: auto
```

**Output:**
```yaml
run_report:
  acceptance_checklist:
    - { id: ac-1, criterion: "...", status: pass, evidence: "test ..." }
  test_coverage: { planned, generated, passing, missing: [] }
  ci: { status, build_time, ... }
  deploy: { test_env, staging, prod }
  cost: { total_usd, breakdown: {...} }
  flags: ["skipped test_gen на fast-path", ...]
```

### Escalation config (Phase A1, schema change)

```yaml
# pipeline YAML (block-level override, редкость)
verify:
  on_fail:
    action: loopback
    target: codegen
    max_iterations: 2
    escalation:
      - { tier: cloud, provider: openrouter, model: smart, max_iterations: 2 }
      - { tier: human, notify: [email, ui], timeout: 86400 }
    # OR: escalation: none      (явный opt-out)
    # OR: escalation: default   (явно использовать defaults — то же что отсутствие поля)
```

```yaml
# application.yaml (global defaults)
workflow:
  escalation-defaults:
    - { tier: cloud, provider: openrouter, model: smart, max_iterations: 2 }
    - { tier: human, notify: [ui], timeout: 86400 }
  escalation-max-budget-usd: 10.00   # hard cap
  preflight-cache-ttl: PT168H
```

```sql
-- migration (Phase B2 for cost, A1 for escalation defaults)
ALTER TABLE project ADD COLUMN escalation_defaults_json TEXT;
ALTER TABLE project ADD COLUMN cloud_budget_usd DECIMAL(10,2) DEFAULT 5.00;
ALTER TABLE llm_call ADD COLUMN cost_usd DECIMAL(10,6);
ALTER TABLE pipeline_run ADD COLUMN total_cost_usd DECIMAL(10,4) DEFAULT 0;
```

---

## Open questions / risks (to resolve at implementation time)

1. **No-progress detector canonical key.** Как именно canonicalize verify issue для сравнения между итерациями. Кандидаты: `(issueType, normalizedFilePath, lineRange ± 5)`, или `sha256(issueType + filename + first 80 chars of message)`. Tunable; стартуем с heuristic, измерим false-positive/false-negative на реальных run'ах.

2. **Boundary value extraction in `test_planning`.** Промпт диктует «применяй BVA», но модель может не знать бизнес-границы (RFC 5321 для email, max int для DB column). Возможный fallback: `agent_verify` после `test_planning` проверяет «для каждого параметра X есть boundary case». Цена — лишний LLM call. Решить эмпирически.

3. **`fast` path и acceptance_checklist.** Если `intake_assessment.path == 'fast'` → skip analysis-decomposition → нет полного acceptance_checklist → `tech_lead_gate` нечего проверять. Решено отказом от tech_lead_gate на fast (`condition`). Но: что если на fast мы случайно сломаем что-то важное? Mitigation: `verify_code + ai_review + ci` — три страховки, должно хватать.

4. **Cloud-tier escalation на vcs_merge / acceptance.** Эскалировать `vcs_merge` (merge conflict) к cloud-LLM бессмысленно — конфликт требует понимания истории, не LLM-навыка. Эти блоки получают `escalation: none` или `escalation: [{tier: human}]` без cloud-step.

5. **Best practices markdown поддержка.** Кто owns обновление `java.md` / `python.md` etc? Принято: PR-ом, ревью через стандартный flow. Но без сheduling — устареет. Mitigation: на C1 запускаем минимальный стартовый набор (5-7 правил для Java), расширяем по запросу.

6. **Preflight для монорепо.** На реально большом монорепо (50+ модулей) preflight всего проекта = 30+ минут. Cache спасает на повторных run'ах с тем же SHA, но первый запуск медленный. Workaround: `preflight.scope: affected` в CLAUDE.md как opt-in (использует output analysis, но pipeline-граф усложняется — preflight после analysis). Не делаем на Phase A2; добавим если оператор будет страдать.

7. **Visual test infrastructure detection.** На Phase D1: как определить «у проекта есть Playwright»? Сейчас планируется через `context_scan.tech_stack.has_ui` + проверка `package.json` на наличие playwright/cypress dependency. Может быть false negative (есть UI, но автотестов нет вообще). Mitigation: на D1 стартуем с conservative — если deps нет, skip с warning.

---

## References

- `docs/plans/phase1-plan.md` — предыдущая фаза tool-use core, фундамент для context_scan
- `memory/project_smart_intake.md` — дизайн smart intake (частично пересекается с B1 intake_assessment)
- `memory/project_smart_checklist.md` — acceptance_checklist (используется tech_lead_gate в D2)
- `memory/project_orchestrator_design.md` — OrchestratorBlock (база для tech_lead_gate)
- `memory/plan_block_caching.md` — block cache (preflight cache следует похожему паттерну)
- `memory/project_pipeline_validator.md` — PipelineConfigValidator (нужен для валидации новых escalation/preflight полей)
- `CLAUDE.md` — главный source-of-truth архитектуры

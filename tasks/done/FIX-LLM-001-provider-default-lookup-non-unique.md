---
id: FIX-LLM-001
title: findByTypeAndIsDefaultTrue падает с NonUniqueResultException при per-project defaults
created: 2026-05-19
status: planned
---

## Как сейчас

Все 6 `*ProviderClient.buildWebClient()` (и `OllamaEmbedder`, и `EntryPointResolver`, и `PipelineRunner.resolveIntegration`) делают двухступенчатый lookup интеграции:

1. `findByTypeAndIsDefaultTrueAndProjectSlug(type, slug)` — project-scoped
2. `findByTypeAndIsDefaultTrue(type)` — глобальный fallback

Файлы и строки:

- `workflow-core/src/main/java/com/workflow/llm/provider/AllTokensProviderClient.java:71-77`
- `workflow-core/src/main/java/com/workflow/llm/provider/AITunnelProviderClient.java:62`
- `workflow-core/src/main/java/com/workflow/llm/provider/OpenRouterProviderClient.java:62`
- `workflow-core/src/main/java/com/workflow/llm/provider/OllamaProviderClient.java:466,500`
- `workflow-core/src/main/java/com/workflow/llm/provider/VllmProviderClient.java:400`
- `workflow-core/src/main/java/com/workflow/llm/provider/ClaudeCliProviderClient.java:196,214`
- `workflow-core/src/main/java/com/workflow/knowledge/OllamaEmbedder.java:125`
- `workflow-core/src/main/java/com/workflow/core/EntryPointResolver.java:403`
- `workflow-core/src/main/java/com/workflow/core/PipelineRunner.java:543`
- `workflow-core/src/main/java/com/workflow/blocks/TaskMdInputBlock.java:341`

Метод репозитория: `IntegrationConfigRepository.findByTypeAndIsDefaultTrue(IntegrationType)` возвращает `Optional<IntegrationConfig>` через Spring Data convention → `getSingleResult()` → `NonUniqueResultException` если в БД несколько строк типа Х с `is_default=true`.

В реальном стейте у нас семь проектов с настроенным ALLTOKENS, шесть из них (`e2e-bugfix`, `e2e-code-review`, `e2e-docs`, `e2e-feature-generic`, `e2e-refactor`, `e2e-write-tests`) имеют `is_default=true`. Когда warcard с `defaultProvider=ALLTOKENS` и **своим** ALLTOKENS-row, у которого `is_default=false`, доходит до fallback — глобальный query матчит все 6 e2e-строк и взрывается.

Конкретный stacktrace (run `dce33b4a-89e4-4434-8826-eee3955206bb`, блок `intake_assessment`, 2026-05-19):
```
Caused by: org.hibernate.NonUniqueResultException: Query did not return a unique result: 6 results were returned
  at com.workflow.llm.provider.AllTokensProviderClient.buildWebClient(AllTokensProviderClient.java:76)
```

Костыль 2026-05-19: warcard ALLTOKENS row id=10 вручную помечен `is_default=true` через PUT `/api/integrations/10` — project-scoped query попадает первой, fallback не вызывается. Костыль не решает корень: любой другой проект, у которого `defaultProvider`-row отмечен `is_default=false` (или вообще отсутствует), всё ещё попадёт в багованный fallback.

## Как надо

Глобальный fallback семантически означает «row без `projectSlug`, помеченный как is_default». Сейчас он матчит ANY row с `is_default=true` независимо от `projectSlug` — это и есть источник non-unique. Два варианта:

- **A (предпочтительно)**: добавить `findByTypeAndIsDefaultTrueAndProjectSlugIsNull(IntegrationType)` в `IntegrationConfigRepository` и заменить все 11+ вызовов `findByTypeAndIsDefaultTrue` на новый метод. «Глобальная default-интеграция» по определению не имеет `projectSlug`.
- **B (страховочно)**: дополнительно сделать `findFirstByTypeAndIsDefaultTrue` (Spring Data `First` → возвращает первую строку по primary key) — он не взрывается даже если invariant нарушен. Использовать только как safety net в местах, где правильное поведение неопределимо.

Уникальность `(type, projectSlug, is_default=true)` валидирующая через DB constraint — отдельный nice-to-have, не блокирующий.

## Вне scope

- Не трогать `findByTypeAndIsDefaultTrueAndProjectSlug` (project-scoped lookup) — он работает.
- Не менять public API контроллера `/api/integrations`.
- Не добавлять unique constraint в Liquibase/JPA — потребует data migration по существующим проектам с дублями default'ов.
- Не править e2e-* фикстуры данных — они уже в legacy состоянии; правильнее, чтобы код был устойчив к этому.

## Критерии приёмки

- [ ] В `IntegrationConfigRepository` появился метод `findByTypeAndIsDefaultTrueAndProjectSlugIsNull(IntegrationType type)` (вариант A) ИЛИ `findFirstByTypeAndIsDefaultTrue(IntegrationType type)` (вариант B).
- [ ] Все 11+ call-sites выше используют новый метод вместо `findByTypeAndIsDefaultTrue`.
- [ ] Старый `findByTypeAndIsDefaultTrue` либо удалён, либо помечен `@Deprecated` с javadoc-ссылкой на тикет.
- [ ] Новый unit-тест `IntegrationConfigRepositoryTest`: создаёт 3 ALLTOKENS row (один с `projectSlug=null isDefault=true`, два с `projectSlug=A/B isDefault=true`), новый query возвращает строго первый, старый бы кинул `NonUniqueResultException`.
- [ ] `gradle build` зелёный (workflow-core, без флагов).
- [ ] WarCard pipeline проходит блок `intake_assessment` под warcard `defaultProvider=ALLTOKENS` после ребилда и отката data-fix `id=10 isDefault=true → false`.

## Реализовано

<!-- заполнить при переводе в done/ -->

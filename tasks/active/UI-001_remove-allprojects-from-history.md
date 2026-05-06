# UI-001 Убрать allProjects=true из вкладок «История» и «Активные» внутри проекта

**Блок:** UI / project scope
**Приоритет:** Высокий

## Цель
Внутри вкладки конкретного проекта (например `Skill Marketplace` → `История`) показывать только runs этого проекта, а не всю систему. Сейчас там видны runs со всех проектов — фильтр `X-Project-Slug` игнорируется потому что фронт принудительно шлёт `allProjects=true`.

## Текущее состояние
1. `workflow-ui/src/pages/RunHistoryPage.tsx`, строка ~42 — в вызове `api.listRuns({...})` передаётся `allProjects: true` всегда. Этот таб рендерится внутри `ProjectWorkspacePage` (роут `/projects/:slug/history`) — то есть в скоупе конкретного проекта.
2. `workflow-ui/src/pages/ActiveRunsPage.tsx`, строка ~40 — аналогично. Этот компонент **используется в двух местах**: глобальная страница `/runs/active` (там allProjects=true оправдан) И внутри `/projects/:slug/active` (а там — нет).

Backend на API уровне фильтрует корректно: `RunController.list(...)` читает `ProjectContext.get()` (из header `X-Project-Slug`) и добавляет predicate `cb.equal(root.get("projectSlug"), currentProject)`. Если запрос с `allProjects=true` — фильтр не применяется.

## Что сделать

### RunHistoryPage.tsx
Удалить строку `allProjects: true,` из вызова `api.listRuns(...)`. История запусков внутри проекта должна быть только для этого проекта. Глобальной страницы «История всех проектов» нет, и она не требуется в этой задаче.

### ActiveRunsPage.tsx
Сделать `allProjects` опциональным prop (default `false`):
- В `App.tsx` (или там где роутится `/runs/active`) при использовании на глобальной странице передавать `allProjects={true}`.
- Внутри `ProjectWorkspacePage` (вкладка `active`) использовать без этого prop — будет `false`, фильтр по проекту сработает.

Если глобальная страница `/runs/active` отсутствует или не используется — просто удалить `allProjects: true` так же как в `RunHistoryPage.tsx`.

### Тесты
Если есть Playwright UI-тесты на `RunHistoryPage` / `ActiveRunsPage` — обновить их моки чтобы не требовать `allProjects=true`. Обычно это в `tests/ui/` или `tests/fixtures/api-mocks.ts`.

## Definition of Done
- В `RunHistoryPage.tsx` нет `allProjects: true`.
- В `ActiveRunsPage.tsx` либо нет `allProjects: true`, либо это conditional prop.
- `npm run build` (tsc + vite build) проходит без ошибок.
- Если добавлены/изменены тесты — `npm test` (Playwright) проходит.
- На UI: открытие `/projects/skill-marketplace/history` показывает только skill-marketplace runs; `/projects/personal-assistant/history` — только personal-assistant runs. (Проверить через скриншот / `curl /api/runs` с соответствующим X-Project-Slug.)

## Зависимости
Нет.

## Acceptance checklist
- [ ] `allProjects: true` удалён из `RunHistoryPage.tsx` вызова `api.listRuns`
- [ ] `ActiveRunsPage.tsx` либо не передаёт `allProjects: true` для project-scoped использования, либо параметризован
- [ ] `npm run build` зелёный
- [ ] Никаких других изменений в файлах не должно быть (минимальный diff)

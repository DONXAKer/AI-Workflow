# mini-target-repo (E2E fixture)

Минимальный TypeScript-проект, используемый Playwright-тестами как target
working-directory для AI-Workflow pipeline-прогонов.

`helpers/target-repo.ts::prepareTargetRepo()` копирует эту папку в
`os.tmpdir()/wf-e2e/<spec-uuid>/`, инициализирует git-репозиторий и регистрирует
путь как `workingDir` тестового Project в UI. Каждый запуск pipeline получает
свежую копию, чтобы блоки `git_setup`/`codegen`/`git_commit` могли работать с
чистым деревом.

Файлы:
- `WF-E2E-001-goodbye.md` — task.md по convention `<FEAT-ID>-<slug>.md` (parser
  в `TaskMdInputBlock` ожидает именно такое имя).
- `src/hello.ts` — единственный source-файл; pipeline должен добавить в него
  `goodbye()`.
- этот README.

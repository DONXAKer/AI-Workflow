# Plan: Расширение функционала запуска — задачи из папки + бизнес-фича

## Context

Пользователь хочет три связанных улучшения рабочего пространства проекта:

1. **Описание бизнес-фичи → запуск** — в SmartStartTab добавить однострочный ввод «Описание бизнес-фичи» с кнопкой, которая сразу запускает пайплайн (изучение проекта + детализация + формирование задач) без двухшагового flow «Анализировать → Запустить».
2. **Папка задач в настройках** — на вкладке Settings добавить поле `tasksDir` (PathInput), чтобы оператор мог задать папку со списком задач вместо захардкоженного `tasks/active`.
3. **Выбор файла задачи** — там, где сейчас просто текстовое поле для `task_file`, показывать список `.md`-файлов из настроенной папки задач с возможностью кликнуть и подставить путь; при наличии заголовка в файле — отображать его как подпись.

## Изменения

### Backend

#### 1. `Project.java`
Добавить поле:
```java
/** Relative path inside workingDir where task .md files live. Defaults to "tasks/active". */
@Column(name = "tasks_dir")
private String tasksDir;
```
Getter/setter + в `getEffectiveTasksDir()` возвращать `tasksDir != null ? tasksDir : "tasks/active"`.

#### 2. `ProjectController.java`
- В `PUT /{slug}` добавить ветку `if (body.getTasksDir() != null) existing.setTasksDir(body.getTasksDir());`
- Добавить новый endpoint:
  ```
  GET /api/projects/{slug}/tasks
  ```
  Ищет `*.md`-файлы в `project.workingDir / project.effectiveTasksDir`.
  Для каждого файла читает первую строку `^#+ (.+)` как `title`.
  Возвращает JSON-массив: `[{name, path, title}]`.

#### 3. `SmartDetectService.java`
Заменить захардкоженный `"tasks/active"` (строки ~160-161) на `project.getEffectiveTasksDir()`.
Для этого нужно передать объект `Project` в метод `buildFacts` (он уже получает `workDir` — теперь получает ещё и `effectiveTasksDir`).

### Frontend

#### 4. `src/types.ts`
В `ProjectInfo` добавить:
```typescript
tasksDir?: string | null
```

#### 5. `src/services/api.ts`
Добавить метод:
```typescript
listProjectTasks: (slug: string): Promise<{ name: string; path: string; title: string | null }[]> =>
  request(`${BASE}/projects/${slug}/tasks`),
```

#### 6. `src/pages/project/SettingsTab.tsx`
- Добавить `tasksDir` state (`useState('')`)
- При загрузке проекта: `setTasksDir(found.tasksDir ?? '')`
- В `save()`: включить `tasksDir` в тело PUT
- В разделе «Рабочая директория / Pipeline-конфиги» добавить третий PathInput:
  ```
  Папка задач (tasks)
  [PathInput value={tasksDir} onChange={setTasksDir} placeholder="tasks/active"]
  Подпись: «Папка с .md-файлами задач. По умолчанию tasks/active относительно рабочей директории.»
  ```

#### 7. `src/pages/project/LaunchTab.tsx`
Когда среди `selectedEntryPoint.inputFields` есть поле с `name === 'task_file'`, рядом с текстовым инпутом показывать «Задачи из папки»:
- При монтировании/смене entry point делать `api.listProjectTasks(slug)` (slug из `useParams`)
- Показывать список файлов как кликабельные строки (имя + заголовок из markdown)
- Клик подставляет `path` в поле `task_file`
- Загрузка/ошибка обрабатываются локально (не блокируют основной flow)
- Секция имеет заголовок «Выберите задачу» над списком

#### 8. `src/pages/project/SmartStartTab.tsx`
Добавить блок **над** существующим textarea «Что нужно сделать?»:
```
┌─ Быстрый запуск ──────────────────────────────────────────┐
│ [Описание бизнес-фичи...                               ]  │
│                                    [Создать задачи ▶]     │
└───────────────────────────────────────────────────────────┘
```
- Однострочный `<input type="text">` (не textarea)
- Кнопка «Создать задачи» напрямую вызывает `api.startRun` с `entryPointId: 'from_scratch'` и `requirement: featureInput` без предварительного smart-detect
- После запуска навигирует на страницу рана (как и существующий flow)
- Ошибки запуска показываются под строкой
- Блок отделён горизонтальным разделителем от существующего textarea-flow

## Порядок реализации

1. Backend: Project.java → ProjectController.java → SmartDetectService.java
2. Frontend: types.ts → api.ts → SettingsTab.tsx → LaunchTab.tsx → SmartStartTab.tsx

## Проверка

1. В Settings для проекта задать `tasksDir = tasks/active` → сохранить → перезагрузить — поле должно сохраниться.
2. Положить `.md`-файл в `<workingDir>/tasks/active/` с заголовком `# My Feature` → открыть LaunchTab → выбрать entry point с `task_file` → в секции «Выберите задачу» должен появиться файл с заголовком «My Feature».
3. Кликнуть на файл → поле `task_file` заполняется путём.
4. В SmartStartTab ввести «Добавить авторизацию через OAuth» → «Создать задачи» → должен запуститься ран.
5. В SmartDetectService убедиться, что `facts.hasActiveTasks` теперь смотрит в `project.effectiveTasksDir`, а не в захардкоженный `tasks/active`.

<!--
  Именование файла: PROJ-NNN_короткий-slug.md  (feat_id = PROJ-NNN, slug = короткий-slug)
  Положить в папку tasks/active/ проекта.

  Как работает пайплайн:
  - title      → заголовок git-коммита и PR
  - as_is      → контекст для блока analysis
  - to_be      → основа для блока plan и codegen
  - acceptance → Definition of Done для блока review
  - Технический контекст → (body) подсказывает plan-агенту какие файлы смотреть первыми

  Чем конкретнее "Как надо" и "Технический контекст" — тем точнее результат.
-->

# Заголовок задачи (станет заголовком коммита)

## Как сейчас
Опиши текущее поведение и почему это проблема. Факты, не оценки.
Ссылки на конкретные URL, компоненты, поля БД — если применимо.

## Как надо
Целевое поведение. Чем конкретнее — тем точнее реализация:
- API-контракт: метод, путь, тело запроса/ответа
- UI: что изменится, в каком состоянии
- БД: новые поля, индексы, миграции
- Бизнес-правила и ограничения

## Вне scope
- Что явно НЕ делаем в этой задаче
- Смежные улучшения — оставляем на потом

## Критерии приёмки
- [ ] Верифицируемый критерий (можно проверить тестом или вручную)
- [ ] Ещё один критерий

## Технический контекст
<!--
  Укажи файлы и компоненты, которые нужно изменить или которые дают важный контекст.
  Агент-планировщик начнёт с них — это сокращает количество итераций поиска.
-->
- Файлы для изменения: 
- Модели / сущности: 
- API-эндпоинты: 
- Команда сборки/проверки: 

---
<!--
## Запуск через AI-Workflow

# 1. Логин (один раз)
curl -s -c /tmp/wf.cookie -X POST http://localhost:8020/api/auth/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}'
COOKIE=$(grep JSESSIONID /tmp/wf.cookie | awk '{print $7}')
XSRF=$(grep XSRF /tmp/wf.cookie | awk '{print $7}')

# 2. Старт (подставить имя файла в task_file)
curl -s \
  -H "Cookie: JSESSIONID=$COOKIE; XSRF-TOKEN=$XSRF" \
  -H "X-XSRF-TOKEN: $XSRF" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8020/api/runs \
  -d '{
    "configPath": "./config/ИМЯ_ПРОЕКТА.yaml",
    "entryPointId": "from_task_file",
    "autoApproveAll": false,
    "inputs": { "task_file": "/projects/ИМЯ_ПРОЕКТА/tasks/active/PROJ-NNN_slug.md" }
  }'

# 3. Одобрить plan (UI иногда падает на сложном JSON — безопаснее через API)
curl -s -X POST \
  -H "Cookie: JSESSIONID=$COOKIE; XSRF-TOKEN=$XSRF" \
  -H "X-XSRF-TOKEN: $XSRF" \
  -H "Content-Type: application/json" \
  "http://localhost:8020/api/runs/{RUN_ID}/approval" \
  -d '{"blockId":"plan","decision":"APPROVE"}'
-->

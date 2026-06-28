# Flyway — обязательные требования

## Undo-скрипты
- Для каждой миграции `V<N>__<name>.sql` создавать парный файл `U<N>__<name>.sql` (undo script).
- Undo-скрипт должен полностью отменять изменения основной миграции (DROP, REVERT).
- Naming: `V1.2__add_user_email.sql` → `U1.2__add_user_email.sql`.

## Идемпотентность
- Использовать `IF NOT EXISTS` / `IF EXISTS` для DDL чтобы повторный запуск не падал.
- Repeatable-миграции (`R__name.sql`) должны быть полностью идемпотентны.

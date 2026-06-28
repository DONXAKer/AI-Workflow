# MySQL / MariaDB — обязательные требования

## Идемпотентность DDL
- `CREATE TABLE IF NOT EXISTS`
- `CREATE INDEX IF NOT EXISTS` (MySQL 8.0+; для старых версий — проверять через INFORMATION_SCHEMA)
- `DROP TABLE IF EXISTS`

## Миграции
- Использовать транзакции для DDL-изменений где возможно (не все операции транзакционны в MySQL).
- Добавление колонки на большую таблицу — использовать `pt-online-schema-change` или `gh-ost`.

## Запросы
- Явно указывать charset: `CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`.
- Не использовать `SELECT *` в production-коде.

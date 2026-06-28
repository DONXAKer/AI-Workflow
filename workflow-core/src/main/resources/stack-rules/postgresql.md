# PostgreSQL — обязательные требования

## Идемпотентность DDL
- `CREATE TABLE IF NOT EXISTS`
- `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` (PostgreSQL 9.6+)
- `CREATE INDEX IF NOT EXISTS` / `CREATE INDEX CONCURRENTLY IF NOT EXISTS`
- `DROP TABLE IF EXISTS` / `DROP COLUMN IF EXISTS`

## Индексы
- Новые индексы на нагруженных таблицах создавать через `CREATE INDEX CONCURRENTLY` — не блокирует запись.
- Индекс на поле внешнего ключа обязателен если таблица > 1000 строк.

## Запросы
- Не использовать `SELECT *` в production-коде — явно перечислять колонки.
- N+1 запросы недопустимы: использовать JOIN или batch-загрузку.

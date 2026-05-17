При работе с PostgreSQL следуй этим принципам:

- Используй UUID для первичных ключей (не SERIAL)
- Названия таблиц в snake_case, множественное число
- Названия колонок в snake_case
- Индексы для часто используемых запросов (WHERE, JOIN, ORDER BY)
- Используй JSONB для структурированных данных, не для реляционных
- Внешние ключи с ON DELETE CASCADE/SET NULL по смыслу
- Timestamps: created_at, updated_at с DEFAULT NOW()
- Мягкое удаление через deleted_at NULLABLE
- Используй транзакции для связанных изменений
- Оптимизируй запросы через EXPLAIN ANALYZE
- Используй connection pooling (HikariCP в Spring Boot)
- Резервные копии и point-in-time recovery
- Мониторинг через pg_stat_statements
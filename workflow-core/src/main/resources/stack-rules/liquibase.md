# Liquibase — обязательные требования

## Rollback — обязателен в каждом changeSet
- Каждый `<changeSet>` ОБЯЗАН содержать элемент `<rollback>`.
- Без `<rollback>` changeSet считается незавершённым и будет отклонён в review.

## Примеры rollback по операции
- Добавление колонки: `<rollback><dropColumn tableName="t" columnName="c"/></rollback>`
- Создание таблицы: `<rollback><dropTable tableName="t"/></rollback>`
- Добавление индекса: `<rollback><dropIndex tableName="t" indexName="idx_name"/></rollback>`
- Добавление внешнего ключа: `<rollback><dropForeignKeyConstraint baseTableName="t" constraintName="fk_name"/></rollback>`
- Вставка данных: `<rollback><delete tableName="t"><where>id = '...'</where></delete></rollback>`
- Переименование колонки: `<rollback><renameColumn tableName="t" oldColumnName="new" newColumnName="old"/></rollback>`

## Именование
- id changeSet: `YYYY-MM-DD-N-краткое-описание` (уникально, информативно).
- author: имя разработчика или `ai-sdlc`.

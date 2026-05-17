# Java Guidelines

Используй Java 21 с современными возможностями:

- Предпочитай `var` для локальных переменных когда тип очевиден
- Используй `record` для immutable data классов вместо POJO
- Применяй `switch` expressions (Java 14+) вместо традиционных switch
- Используй text blocks (Java 15+) для многострочных строк
- Применяй pattern matching в instanceof и switch (Java 16+)
- Используй sealed классы для иерархий с ограниченным наследованием

## Структура пакетов
- Следуй соглашению `com.company.domain.layer`
- Controller классы в `...web` или `...api` пакетах
- Service классы в `...service` пакетах  
- Repository классы в `...repository` пакетах
- Entity классы в `...domain` или `...entity` пакетах

## Исключения
- Используй unchecked exceptions для бизнес-логики
- Создавай специфичные исключения для доменной области
- Не лови `Exception` - используй конкретные типы

## Тестирование
- Используй JUnit 5
- Применяй @ParameterizedTest для множественных сценариев
- Используй MockMvc для REST endpoint тестов
- Применяй @Testcontainers для интеграционных тестов с БД
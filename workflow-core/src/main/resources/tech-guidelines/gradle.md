При работе с Gradle следуй этим принципам:

- Используй Kotlin DSL (build.gradle.kts) для новых проектов
- Версия Gradle 8.12+ с Java 21
- Структура: settings.gradle.kts, build.gradle.kts, subprojects/
- Зависимости через version catalogs (libs.versions.toml)
- Используй plugin DSL вместо apply()
- Тесты через JUnit 5 с proper test source sets
- Multi-module проекты для монолитов
- Используй Gradle Wrapper для воспроизводимости
- Кэширование через build cache и configuration cache
- Оптимизация сборки: parallel execution, daemon
- Публикация артефактов через maven-publish
- Используй spotless/checkstyle для code quality
- Docker образы через jib или docker plugin
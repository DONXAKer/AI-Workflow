# CLAUDE_NOTES — AI-Workflow
<!-- Управляется агентом supervisor. Максимум 10 пунктов на секцию. -->

## CONSTRAINTS
- Никогда не делать `git push --force` в ветку main
- Не изменять схему БД без Liquibase-миграции (в CRM-проектах)
- Не запускать `gradle build` под JDK 24+ — ломает Lombok (использовать JDK 21)

## STACK
- Java 21, Spring Boot 3.4.4, Liquibase (XML changelogs), QueryDSL
- React 18 + TypeScript + Ant Design (frontend crm2_frontend)
- JDK 21 для тестов: JAVA_HOME=/Users/home/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home
- CRM2-проект: `/Users/home/Code/Abccred/crm/crm2`

## DECISIONS
- Liquibase миграции в `src/main/resources/db/changelog/migration/` с именами `YYYYMMDD_DESCRIPTION.xml`
- CommercialCondition содержит Installment с type-дискриминатором: 'installment'/'delta'/'beta'/'benefit'
- Discount formula: max(0, percent - COALESCE(technology358, 0))
- typeCredit.name mapping: "классическ"→installment, "дельта"→delta, "бета"→beta, "льготн"→benefit

## KNOWN_ERRORS
- JDK 24/25 ломает Lombok annotation processor (NoSuchFieldException TypeTag::UNKNOWN) — использовать JDK 21
- Pre-existing test failures: BlankControllerTest, CreditContractServiceImplTest, DigitalKassaReceiptControllerTest (не связаны с codegen-задачами)
- QueryDSL Q-классы отсутствуют при чистом checkout — нужен `compileJava` с JDK 21 для генерации

## BOUNDARIES
- Не изменять `db.changelog-master.xml` без добавления реального файла миграции
- Не трогать существующие entity-поля без миграции

## OPERATOR_PREFS
- Стиль кода: Lombok @Getter/@Setter, @RequiredArgsConstructor, без явных конструкторов там где можно
- Тесты: Mockito @InjectMocks + @Mock, unit-тесты без Spring контекста

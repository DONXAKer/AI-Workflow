# Lombok Guidelines

Ключевые аннотации: @Data, @Value, @Builder, @RequiredArgsConstructor, @Getter, @Setter, @AllArgsConstructor.

Используй Lombok для сокращения бойлерплейт кода:

## Аннотации
- `@Data` для POJO с геттерами/сеттерами/toString/equals/hashCode
- `@Value` для immutable классов (вместо record если нужна совместимость)
- `@Builder` для fluent API создания объектов
- `@RequiredArgsConstructor` для dependency injection через final поля
- `@AllArgsConstructor` только когда действительно нужны все поля

## Best Practices
- Не используй `@Data` на entity классах с JPA - используй `@Getter`/`@Setter`
- Предпочитай `@Builder` вместо `@Data` для immutable DTO
- Используй `@Singular` в билдерах для коллекций
- Применяй `@Cleanup` для try-with-resources ресурсов

## Предостережения
- `@EqualsAndHashCode` на JPA entity может вызвать проблемы с lazy loading
- `@ToString` на entity с bidirectional relationships может вызвать StackOverflow
- Используй `@EqualsAndHashCode.Exclude` для полей с циклическими зависимостями
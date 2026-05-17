# Spring Boot Guidelines

Используй Spring Boot 3.4+ с современными практиками:

## Конфигурация
- Используй `@ConfigurationProperties` для типизированной конфигурации
- Предпочитай constructor injection вместо field injection
- Используй `@ConditionalOnProperty` для опциональных бинов
- Применяй `@Profile` для environment-specific конфигураций

## REST API
- Используй `@RestController` и `@RequestMapping` для REST endpoints
- Применяй `@GetMapping`, `@PostMapping` и т.д. вместо generic `@RequestMapping`
- Используй `@Validated` и `@Valid` для валидации входных данных
- Возвращай `ResponseEntity` для полного контроля над HTTP response

## Безопасность
- Используй Spring Security с JWT токенами
- Применяй `@PreAuthorize` для method-level security
- Используй password encoding с BCrypt
- Настраивай CORS для frontend интеграции

## Data Access
- Используй Spring Data JPA с repositories
- Применяй `@Transactional` для сервисных методов
- Используй `@Entity` и `@Table` для маппинга сущностей
- Предпочитай JPA Criteria API или QueryDSL для динамических запросов

## Тестирование
- Используй `@WebMvcTest` для controller layer
- Применяй `@DataJpaTest` для repository layer  
- Используй `@SpringBootTest` с random port для интеграционных тестов
- Применяй Testcontainers для реальной БД в тестах
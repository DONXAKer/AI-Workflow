**Spring Boot {version}**
- Build tool: Gradle with Kotlin DSL (`build.gradle.kts`). Gradlew is in `api/` — run as `cd api && ./gradlew <task>`.
- Migrations: Flyway — check the last V-number in `api/src/main/resources/db/migration/` before creating a new one.
- Use `@RestController` + `@RequestMapping`; prefer existing controllers over new ones when adding endpoints.
- Security: `@PreAuthorize` on all non-public endpoints; use method-security expressions (`hasRole`, `hasAuthority`).
- OpenAPI/Springdoc: `@Operation(summary=...)` + `@ApiResponse` on every new/changed endpoint.
- JPA: avoid N+1 — use `@EntityGraph` or JOIN FETCH for collections loaded in request scope.
- DTOs: use records + `@Schema` for Springdoc; never expose JPA entities directly in responses.
- Tests: unit tests for service layer (Mockito); integration tests with Testcontainers for repository/controller.

# Java Best Practices

Curated short list — apply when the surrounding code already follows similar
patterns. Not a style guide; not a substitute for code review.

## Rules

1. **Records for DTOs / value objects** (Java 14+).
   When you need an immutable carrier of named fields, prefer `record` over a
   POJO with getters/setters. Compact constructors give you validation; records
   auto-generate `equals`/`hashCode`/`toString`.

2. **`@Transactional(readOnly = true)` on query methods.**
   Spring Data JPA query methods that don't mutate data should be marked
   read-only — Hibernate skips dirty-checking and the connection pool can route
   to a read replica when configured.

3. **Constructor injection with `@RequiredArgsConstructor` (Lombok).**
   Field injection (`@Autowired private X x;`) makes testing harder and hides
   dependencies. Constructor injection makes them explicit; `@RequiredArgsConstructor`
   on the class removes the boilerplate constructor.

4. **AssertJ over Hamcrest / vanilla JUnit assertions.**
   `assertThat(x).isEqualTo(y)` reads better than `assertEquals(y, x)`, has
   fluent chaining (`.isNotNull().hasSize(3)`), and gives substantially better
   failure messages on collections.

5. **`Optional` only as return type** — never as a field, parameter, or in
   collections. `Optional<X>` on a field/parameter is unidiomatic; use null +
   `@Nullable` annotation, or split the API.

6. **Pattern matching for instanceof (Java 16+).**
   `if (obj instanceof Foo f) { ... f.bar() ... }` — eliminates the redundant
   cast and scope-leak of the old idiom.

7. **Switch expressions with exhaustive patterns (Java 21).**
   For sealed hierarchies or enums, use `return switch (x) { case A a -> ... }`
   — compiler enforces exhaustiveness, no fall-through bugs.

8. **`@SneakyThrows` should be a last resort.**
   Hiding checked exceptions makes the call site lie about what it throws.
   Declare them, or wrap as `RuntimeException` with explicit cause when truly
   unrecoverable.

## Anti-patterns (flag these, don't fix without context)

- `static` mutable singletons (use Spring beans or pass via constructor).
- Catching `Exception` (or worse, `Throwable`) without re-throwing — usually
  hides bugs; catch the narrowest type that can actually happen.
- `String + String + ...` in a tight loop — use `StringBuilder`.
- `Stream.collect(Collectors.toList())` when `.toList()` (Java 16+) is available.

**Java {version}**
- Use records for DTOs, sealed interfaces for discriminated unions.
- Prefer `var` for local variables where type is obvious from the right-hand side.
- Virtual threads (Project Loom) are available — avoid blocking thread pools.
- Use `@NotNull` / `@NotBlank` / `@Valid` for validation; never null-check manually at service boundaries.
- Pattern matching (`instanceof`, switch expressions) preferred over casting chains.
- Text blocks for multi-line strings (SQL, JSON, prompts).

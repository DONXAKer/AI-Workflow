**Redis {version}**
- Used for caching and session/rate-limit state; not the primary store — always tolerate cache miss.
- Set TTL on every key; never store without expiry.
- Prefer structured key naming: `{domain}:{entity}:{id}` (e.g., `user:session:abc123`).
- Use Lua scripts or MULTI/EXEC for atomic multi-key operations.
- Spring: use `@Cacheable` / `@CacheEvict` with a named `CacheManager` bean; avoid raw `RedisTemplate` unless batch ops are needed.

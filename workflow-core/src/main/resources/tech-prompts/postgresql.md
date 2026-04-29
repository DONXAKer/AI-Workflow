**PostgreSQL {version}**
- Use JSONB for semi-structured data (manifests, metadata); index with GIN when queried.
- Use PostgreSQL arrays (`text[]`, `uuid[]`) for simple multi-value fields instead of join tables.
- Always add indexes on foreign keys and columns used in WHERE / ORDER BY.
- Prefer `RETURNING` clause in INSERT/UPDATE over a separate SELECT.
- For full-text search: `tsvector` + `tsquery` with GIN index; for similarity: `pg_trgm` extension.
- 152-ФЗ / 289-ФЗ compliance: personal data columns must be noted — document which fields contain PD.

**MinIO / S3-compatible object storage**
- Used for binary artifact storage (skill packages, attachments). API is S3-compatible.
- Bucket per logical domain (e.g., `skills-artifacts`, `user-avatars`); never use a single flat bucket for everything.
- Pre-signed URLs for client downloads — never stream through the application backend.
- For upload: pre-signed PUT URL → client uploads directly → backend validates after completion.
- Set lifecycle policies (expiration) on temporary/upload-staging buckets.

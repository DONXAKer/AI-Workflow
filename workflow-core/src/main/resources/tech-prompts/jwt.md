**JWT Auth**
- Access token: 24h TTL, standard claims (`sub`, `iat`, `exp`, `roles`).
- Download token: separate short-lived token with `sub=download` claim + artifact scope — used only for file downloads.
- Validate signature and expiry on every protected request; never trust claims without verification.
- Never log token values — log only `sub` or truncated token ID.
- Refresh tokens (if used): store server-side hash, not the token itself.

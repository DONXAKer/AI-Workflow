**Next.js {version}**
- App Router with React Server Components by default; add `"use client"` only when browser APIs or hooks are needed.
- TypeScript strict mode — no `any`; define explicit types for all API responses.
- Fetch data in Server Components using `fetch` with `cache` / `revalidate`; avoid client-side fetching for initial data.
- API client: reuse existing functions from `web/lib/api.ts` — do not duplicate fetch logic.
- Styling: Tailwind CSS utility classes; do not introduce additional CSS-in-JS libraries.
- Check `web/components/` for existing components before creating new ones.
- Run `cd web && npm run build` and `cd web && npm run type-check` to verify before submitting changes.

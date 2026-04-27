/**
 * Returns the URL for a run detail page.
 * When called from inside a project workspace (/projects/:slug/…), the URL
 * stays within the workspace so the project tab bar remains visible.
 */
export function runHref(runId: string, currentPath: string): string {
  const match = currentPath.match(/^\/projects\/([^/]+)/)
  if (match) return `/projects/${match[1]}/runs/${runId}`
  return `/runs/${runId}`
}

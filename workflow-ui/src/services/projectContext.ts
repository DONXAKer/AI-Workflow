/**
 * Single source of truth for the currently-selected project slug. Stored in localStorage
 * under {@code workflow:currentProjectSlug} by {@code ProjectSwitcher}; every API request
 * attaches it as {@code X-Project-Slug} header via {@link currentProjectSlug}.
 */

export const PROJECT_SLUG_KEY = 'workflow:currentProjectSlug'
export const DEFAULT_PROJECT_SLUG = 'default'

export function currentProjectSlug(): string {
  try {
    return localStorage.getItem(PROJECT_SLUG_KEY) ?? DEFAULT_PROJECT_SLUG
  } catch {
    // SSR or storage unavailable
    return DEFAULT_PROJECT_SLUG
  }
}

export function setCurrentProjectSlug(slug: string): void {
  try {
    localStorage.setItem(PROJECT_SLUG_KEY, slug)
  } catch {
    // ignore
  }
}

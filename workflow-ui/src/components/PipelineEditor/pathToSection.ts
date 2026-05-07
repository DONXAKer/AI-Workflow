import { BlockMetadataDto, FieldSchemaDto } from '../../types'

export type SectionKey = 'essentials' | 'conditions' | 'advanced'

const ESSENTIAL_LOCATIONS = [
  '.depends_on',
  '.id',
  '.block',
  '.phase',
  '.enabled',
  '.approval',
  '.approval_mode',
]

const CONDITIONS_LOCATIONS = [
  '.condition',
  '.verify.on_fail',
  '.on_failure',
]

/**
 * Effective UI level for a field, mirroring {@code GenericBlockForm.effectiveLevel}.
 */
function effectiveLevel(field: FieldSchemaDto): 'essential' | 'advanced' {
  return field.level ?? (field.required ? 'essential' : 'advanced')
}

/**
 * Maps a validator-error location (e.g. {@code "pipeline[2].verify.on_fail.target"})
 * to one of the side-panel section keys, so the side panel can auto-expand the
 * right group when a validation error fires.
 *
 * Returns `'advanced'` as a safe default for unknown locations — but we also
 * console.warn in dev so we don't silently miss a new path type.
 */
export function pathToSection(
  location: string | null | undefined,
  blockMetadata?: BlockMetadataDto,
): SectionKey {
  if (!location) return 'advanced'

  // Strip leading "pipeline[N]"
  const stripped = location.replace(/^pipeline\[\d+\]/, '')

  // Conditions & Retry comes first — `.verify.on_fail.*` and `.on_failure.*`
  // are deeper than just `.verify` so we match those before the broader `.config`.
  for (const prefix of CONDITIONS_LOCATIONS) {
    if (stripped === prefix || stripped.startsWith(prefix + '.') || stripped.startsWith(prefix + '[')) {
      return 'conditions'
    }
  }

  // Essentials: depends_on / phase / enabled / approval / id / block
  for (const prefix of ESSENTIAL_LOCATIONS) {
    if (stripped === prefix || stripped.startsWith(prefix + '.') || stripped.startsWith(prefix + '[')) {
      return 'essentials'
    }
  }

  // For `.config.<field>` look at the block's metadata to decide.
  const configMatch = stripped.match(/^\.config\.([a-zA-Z0-9_]+)/)
  if (configMatch && blockMetadata) {
    const fieldName = configMatch[1]
    const field = blockMetadata.configFields.find(f => f.name === fieldName)
    if (field) return effectiveLevel(field) === 'essential' ? 'essentials' : 'advanced'
    return 'advanced'
  }

  // verify.* (other than on_fail) — keep in essentials (subject/checks/llm_check live there)
  if (stripped.startsWith('.verify')) return 'essentials'

  if (stripped.startsWith('.config')) return 'advanced'

  // agent override fields, raw json, etc → advanced
  if (stripped.startsWith('.agent')) return 'advanced'

  // Dev fallback — `console.warn` here helps surface a new field type that
  // `pathToSection` doesn't know about yet. Quiet enough that prod logs aren't
  // polluted (only fires on validator-error locations the side panel can't map).
  // eslint-disable-next-line no-console
  console.warn(`[pathToSection] unmapped location "${location}" — falling back to 'advanced'`)
  return 'advanced'
}

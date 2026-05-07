import { useId, useMemo } from 'react'
import { BlockRegistryEntry, PipelineConfigDto } from '../../types'

interface Props {
  value: string
  onChange: (next: string) => void
  /** Block currently being edited — excluded from the suggestion list. */
  currentBlockId: string
  config: PipelineConfigDto
  byType: Record<string, BlockRegistryEntry>
  placeholder?: string
  /** data-testid for the underlying input. */
  testId?: string
  /** Optional className override for the input. */
  className?: string
}

/**
 * Free-text input enriched with `<datalist>` autocomplete that suggests
 * `$.{ancestor.id}.{output_field}` references reachable via `depends_on` from
 * the current block.
 *
 * Loopback edges (`verify.on_fail.target`, `on_failure.target`) are explicitly
 * NOT followed — they are runtime control-flow, not data-flow, so a downstream
 * block typically does not have access to outputs from a node it loopbacks to.
 *
 * Free text is allowed (datalist is non-restrictive). Backwards-compatible
 * for blocks that haven't declared `outputs` yet — they simply contribute zero
 * suggestions.
 */
export function OutputsRefPicker({
  value, onChange, currentBlockId, config, byType, placeholder, testId, className,
}: Props) {
  const reactId = useId()
  // useId returns ":r0:"-style strings — datalist id must be plain.
  const listId = `outputs-ref-${reactId.replace(/:/g, '')}`

  const suggestions = useMemo(
    () => computeAvailableRefs(currentBlockId, config, byType),
    [currentBlockId, config, byType],
  )

  return (
    <>
      <input
        type="text"
        list={listId}
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        data-testid={testId}
        className={
          className ??
          'w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 font-mono text-xs focus:outline-none focus:ring-1 focus:ring-blue-500'
        }
      />
      <datalist id={listId} data-testid={testId ? `${testId}-options` : undefined}>
        {suggestions.map(s => (
          <option key={s} value={s} />
        ))}
      </datalist>
    </>
  )
}

/**
 * Walks `depends_on` ancestors of `currentBlockId` (BFS) and returns the
 * flattened list of `$.{ancestor.id}.{output.name}` strings. Loopback edges
 * are not followed.
 */
export function computeAvailableRefs(
  currentBlockId: string,
  config: PipelineConfigDto,
  byType: Record<string, BlockRegistryEntry>,
): string[] {
  const blocks = config.pipeline ?? []
  const byId: Record<string, typeof blocks[number]> = {}
  for (const b of blocks) byId[b.id] = b

  const start = byId[currentBlockId]
  if (!start) return []

  // BFS up the depends_on graph, collecting unique ancestor ids.
  const ancestors: string[] = []
  const seen = new Set<string>([currentBlockId])
  const queue: string[] = [...(start.depends_on ?? [])]
  while (queue.length > 0) {
    const id = queue.shift()!
    if (seen.has(id)) continue
    seen.add(id)
    ancestors.push(id)
    const parent = byId[id]
    if (!parent) continue
    for (const dep of parent.depends_on ?? []) {
      if (!seen.has(dep)) queue.push(dep)
    }
  }

  const out: string[] = []
  for (const id of ancestors) {
    const block = byId[id]
    if (!block) continue
    const meta = byType[block.block]?.metadata
    const outputs = meta?.outputs ?? []
    for (const o of outputs) {
      out.push(`$.${id}.${o.name}`)
    }
  }
  return out
}

export default OutputsRefPicker

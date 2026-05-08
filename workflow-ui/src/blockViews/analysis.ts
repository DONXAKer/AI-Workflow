import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const checklist = out.acceptance_checklist
    if (Array.isArray(checklist) && checklist.length > 0) {
      return { label: `checklist · ${checklist.length} пунктов`, ok: true }
    }
    const complexity = typeof out.estimated_complexity === 'string' ? out.estimated_complexity : ''
    return { label: complexity || '—' }
  },
  fields: [
    { key: 'estimated_complexity', label: 'Сложность', kind: 'string' },
    { key: 'technical_approach', label: 'Технический подход', kind: 'multiline', emphasis: true },
    { key: 'affected_components', label: 'Затронутые компоненты', kind: 'list' },
    { key: 'acceptance_checklist', label: 'Acceptance checklist', kind: 'objList', emphasis: true },
    { key: 'needs_clarification', label: 'Требует уточнений', kind: 'bool' },
  ],
}

import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const goal = typeof out.goal === 'string' ? out.goal.trim() : ''
    if (goal) {
      const preview = goal.length > 60 ? goal.slice(0, 60) + '…' : goal
      return { label: preview, ok: true }
    }
    return { label: 'план' }
  },
  fields: [
    { key: 'goal', label: 'Цель', kind: 'multiline', emphasis: true },
    { key: 'approach', label: 'Подход', kind: 'multiline' },
    // files_to_touch / tools_to_use are newline-/comma-separated STRINGS per
    // orchestrator schema, not arrays. Using kind:list rendered them as "—"
    // because FieldValue treats non-array values as empty for list-kind.
    { key: 'files_to_touch', label: 'Файлы', kind: 'multiline' },
    { key: 'tools_to_use', label: 'Инструменты', kind: 'multiline' },
    { key: 'definition_of_done', label: 'Definition of Done', kind: 'multiline' },
  ],
}

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
    { key: 'files_to_touch', label: 'Файлы', kind: 'list' },
    { key: 'tools_to_use', label: 'Инструменты', kind: 'list' },
    { key: 'definition_of_done', label: 'Definition of Done', kind: 'multiline' },
  ],
}

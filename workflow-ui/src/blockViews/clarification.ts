import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const clarifications = out.clarifications as Record<string, unknown> | undefined
    const count = clarifications && typeof clarifications === 'object' ? Object.keys(clarifications).length : 0
    if (count === 0) return { label: 'не нужна', ok: true }
    return { label: `${count} уточнений`, warn: true }
  },
  fields: [
    { key: 'refined_requirement', label: 'Уточнённое требование', kind: 'multiline', emphasis: true },
    { key: 'approved_approach', label: 'Согласованный подход', kind: 'multiline' },
    { key: 'clarifications', label: 'Вопросы и ответы', kind: 'objList' },
  ],
}

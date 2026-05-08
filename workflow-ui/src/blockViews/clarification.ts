import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    if (out.needs_clarification === false) return { label: 'не нужна', ok: true }
    const q = Array.isArray(out.questions) ? out.questions.length : 0
    if (q > 0) return { label: `${q} вопросов`, warn: true }
    return { label: '—' }
  },
  fields: [
    { key: 'clarified_requirement', label: 'Уточнённое требование', kind: 'multiline', emphasis: true },
    { key: 'needs_clarification', label: 'Нужна уточнение', kind: 'bool' },
    { key: 'questions', label: 'Вопросы', kind: 'list' },
    { key: 'answers', label: 'Ответы', kind: 'objList' },
  ],
}

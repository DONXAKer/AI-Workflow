import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const featId = typeof out.feat_id === 'string' ? out.feat_id : ''
    const title = typeof out.title === 'string' ? out.title : ''
    if (featId && title) {
      const short = title.length > 50 ? title.slice(0, 50) + '…' : title
      return { label: `${featId}: ${short}`, ok: true }
    }
    return { label: title || featId || '—' }
  },
  fields: [
    { key: 'feat_id', label: 'Feat ID', kind: 'string' },
    { key: 'title', label: 'Заголовок', kind: 'string', emphasis: true },
    { key: 'slug', label: 'Slug', kind: 'string' },
    { key: 'complexity', label: 'Сложность', kind: 'string' },
    { key: 'needs_clarification', label: 'Требует уточнений', kind: 'bool' },
    { key: 'as_is', label: 'Как сейчас', kind: 'multiline' },
    { key: 'to_be', label: 'Как надо', kind: 'multiline' },
    { key: 'out_of_scope', label: 'Вне scope', kind: 'multiline' },
    { key: 'acceptance', label: 'Критерии приёмки', kind: 'multiline' },
  ],
}

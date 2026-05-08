import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const text = typeof out.final_text === 'string' ? out.final_text : ''
    if (text) {
      const firstLine = text.split('\n').find(l => l.trim()) ?? ''
      const preview = firstLine.length > 60 ? firstLine.slice(0, 60) + '…' : firstLine
      return { label: preview || `${text.length} chars`, ok: true }
    }
    return { label: '—' }
  },
  fields: [
    { key: 'final_text', label: 'Результат', kind: 'multiline', emphasis: true },
    { key: 'iterations_used', label: 'Итераций', kind: 'number' },
    { key: 'total_cost_usd', label: 'Стоимость', kind: 'number' },
  ],
}

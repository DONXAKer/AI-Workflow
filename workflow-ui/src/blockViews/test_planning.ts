import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const strategy = typeof out.strategy === 'string' ? out.strategy : ''
    const cases = Array.isArray(out.cases) ? (out.cases as unknown[]).length : 0
    const cov = typeof out.coverage_estimate === 'number' ? out.coverage_estimate : 0
    const covPct = Math.round(cov * 100)
    if (strategy === 'none') return { label: 'тесты не нужны (none)', ok: true }
    const stratRu = strategy === 'tdd' ? 'TDD' : 'adaptive'
    return { label: `${stratRu} · ${cases} кейсов · ${covPct}% coverage`, ok: cases > 0 }
  },
  fields: [
    { key: 'strategy', label: 'Стратегия', kind: 'string', emphasis: true },
    { key: 'coverage_estimate', label: 'Оценка покрытия', kind: 'number' },
    { key: 'notes', label: 'Заметки', kind: 'multiline' },
    { key: 'cases', label: 'Test cases', kind: 'objList', emphasis: true },
  ],
}

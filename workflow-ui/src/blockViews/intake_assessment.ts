import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const pct = typeof out.clarity_pct === 'number' ? out.clarity_pct : 0
    const path = typeof out.recommended_path === 'string' ? out.recommended_path : ''
    const pathRu = path === 'fast' ? 'fast (можно сразу кодить)'
      : path === 'full' ? 'full (обычный flow)'
        : path === 'clarify' ? 'clarify (нужны вопросы)'
          : path
    if (path === 'fast') return { label: `${pct}% · ${pathRu}`, ok: true }
    if (path === 'clarify') return { label: `${pct}% · ${pathRu}`, warn: true }
    return { label: `${pct}% · ${pathRu}` }
  },
  fields: [
    { key: 'clarity_pct', label: 'Clarity, %', kind: 'number', emphasis: true },
    { key: 'recommended_path', label: 'Рекомендуемый путь', kind: 'string', emphasis: true },
    { key: 'rationale', label: 'Обоснование', kind: 'multiline' },
    { key: 'clarity_breakdown', label: 'Критерии (по 5)', kind: 'objList' },
  ],
}

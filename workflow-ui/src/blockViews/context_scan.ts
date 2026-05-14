import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const lang = typeof out.language === 'string' ? out.language : '?'
    const conv = Array.isArray(out.code_conventions) ? (out.code_conventions as unknown[]).length : 0
    const bp = Array.isArray(out.applicable_best_practices) ? (out.applicable_best_practices as unknown[]).length : 0
    return { label: `${lang} · ${conv} conv · ${bp} bp`, ok: lang !== 'unknown' }
  },
  fields: [
    { key: 'language', label: 'Язык', kind: 'string', emphasis: true },
    { key: 'tech_stack', label: 'Tech stack', kind: 'objList', emphasis: true },
    { key: 'code_conventions', label: 'Conventions проекта', kind: 'list' },
    { key: 'applicable_best_practices', label: 'Применимые best practices', kind: 'objList' },
    { key: 'suggestions_for_codegen', label: 'Подсказки для codegen', kind: 'list' },
    { key: 'source_files_sampled', label: 'Sample файлов', kind: 'number' },
  ],
}

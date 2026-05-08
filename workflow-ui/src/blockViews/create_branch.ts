import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const branch = out.branch_name
    if (typeof branch === 'string' && branch) return { label: branch, ok: true }
    return { label: 'не создана', fail: true }
  },
  fields: [
    { key: 'branch_name', label: 'Ветка', kind: 'string', emphasis: true },
    { key: 'created', label: 'Создана', kind: 'bool' },
  ],
  inputFields: [
    { key: 'feat_id', label: 'Feat ID', kind: 'string' },
    { key: 'task_file', label: 'Файл задачи', kind: 'string' },
  ],
}

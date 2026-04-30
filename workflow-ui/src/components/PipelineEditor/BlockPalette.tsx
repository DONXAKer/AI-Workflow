import { useMemo, useState } from 'react'
import { Search } from 'lucide-react'
import clsx from 'clsx'
import { BlockRegistryEntry } from '../../types'

interface BlockPaletteProps {
  registry: BlockRegistryEntry[]
  onAdd: (entry: BlockRegistryEntry) => void
}

const CATEGORY_LABEL: Record<string, string> = {
  input: 'Входные данные',
  agent: 'Агенты',
  verify: 'Проверки',
  ci: 'CI/CD',
  infra: 'Инфраструктура',
  output: 'Выходные данные',
  general: 'Прочие',
}

const CATEGORY_ORDER = ['input', 'agent', 'verify', 'ci', 'infra', 'output', 'general']

export function BlockPalette({ registry, onAdd }: BlockPaletteProps) {
  const [q, setQ] = useState('')

  const grouped = useMemo(() => {
    const ql = q.trim().toLowerCase()
    const filtered = ql
      ? registry.filter(e =>
          e.type.toLowerCase().includes(ql) ||
          e.metadata.label.toLowerCase().includes(ql) ||
          (e.description ?? '').toLowerCase().includes(ql))
      : registry
    const out: Record<string, BlockRegistryEntry[]> = {}
    for (const e of filtered) {
      const cat = e.metadata.category || 'general'
      ;(out[cat] ??= []).push(e)
    }
    return out
  }, [registry, q])

  return (
    <div data-testid="block-palette" className="w-60 bg-slate-900 border-r border-slate-800 flex flex-col">
      <div className="p-3 border-b border-slate-800">
        <div className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-2">
          Блоки
        </div>
        <div className="relative">
          <Search className="w-3.5 h-3.5 text-slate-500 absolute left-2 top-2" />
          <input
            type="text"
            value={q}
            onChange={e => setQ(e.target.value)}
            placeholder="Поиск..."
            className="w-full bg-slate-950 border border-slate-700 rounded pl-7 pr-2 py-1.5 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
      </div>
      <div className="flex-1 overflow-y-auto p-2 space-y-3">
        {CATEGORY_ORDER.map(cat => {
          const items = grouped[cat]
          if (!items || items.length === 0) return null
          return (
            <div key={cat}>
              <div className="text-[10px] font-semibold text-slate-500 uppercase tracking-wide mb-1 px-1">
                {CATEGORY_LABEL[cat] ?? cat}
              </div>
              <div className="space-y-0.5">
                {items.map(item => (
                  <button
                    key={item.type}
                    type="button"
                    onClick={() => onAdd(item)}
                    data-testid={`palette-add-${item.type}`}
                    className={clsx(
                      'w-full text-left px-2 py-1.5 rounded text-xs',
                      'bg-slate-800/40 hover:bg-blue-900/40 border border-slate-800 hover:border-blue-700',
                      'text-slate-200 hover:text-blue-200 transition-colors'
                    )}
                    title={item.description}
                  >
                    <div className="font-medium leading-tight">{item.metadata.label}</div>
                    <div className="font-mono text-[10px] text-slate-500 mt-0.5 truncate">
                      {item.type}
                    </div>
                  </button>
                ))}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default BlockPalette

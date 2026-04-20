import { useEffect, useRef, useState, useMemo } from 'react'
import { Terminal, Download, Trash2, Search, X, ClipboardCopy, Check } from 'lucide-react'

interface Props {
  logs: string[]
  /** If provided, a clear button is shown */
  onClear?: () => void
  /**
   * When true the panel is the active/visible tab. Used to trigger a
   * scroll-to-bottom on reveal — necessary because the auto-scroll effect
   * only fires when `logs` changes, so logs that arrived while the panel
   * was hidden (`display:none`) never cause a scroll.
   */
  visible?: boolean
}

const MAX_LOGS = 500

type LogLevel = 'all' | 'error' | 'approval' | 'complete' | 'warning'

const LEVEL_OPTIONS: { key: LogLevel; label: string; textClass: string }[] = [
  { key: 'all',      label: 'Все',        textClass: 'text-slate-300' },
  { key: 'error',    label: 'Ошибки',    textClass: 'text-red-400'   },
  { key: 'approval', label: 'Одобрение', textClass: 'text-amber-400' },
  { key: 'complete', label: 'Готово',    textClass: 'text-green-400' },
  { key: 'warning',  label: 'Предупр.',  textClass: 'text-yellow-500' },
]

function classifyLine(line: string): LogLevel {
  const lower = line.toLowerCase()
  if (lower.includes('error') || lower.includes('failed')) return 'error'
  if (lower.includes('approval')) return 'approval'
  if (lower.includes('complete')) return 'complete'
  if (lower.includes('warn') || lower.includes('cancel')) return 'warning'
  return 'all'
}

function levelTextClass(level: LogLevel): string {
  switch (level) {
    case 'error':    return 'text-red-400'
    case 'approval': return 'text-amber-400'
    case 'complete': return 'text-green-400'
    case 'warning':  return 'text-yellow-500'
    default:         return 'text-slate-400'
  }
}

export default function LogPanel({ logs, onClear, visible = true }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  const [searchQuery, setSearchQuery] = useState('')
  const [levelFilter, setLevelFilter] = useState<LogLevel>('all')
  const [copied, setCopied] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)

  const displayLogs = logs.slice(-MAX_LOGS)

  // Apply search + level filters
  const filteredLogs = useMemo(() => {
    const query = searchQuery.trim().toLowerCase()
    return displayLogs.filter(line => {
      if (levelFilter !== 'all') {
        const lineLevel = classifyLine(line)
        // For 'all' level lines, they pass the error/warning/etc filters only if they match that type
        if (levelFilter === 'error'    && lineLevel !== 'error')    return false
        if (levelFilter === 'approval' && lineLevel !== 'approval') return false
        if (levelFilter === 'complete' && lineLevel !== 'complete') return false
        if (levelFilter === 'warning'  && lineLevel !== 'warning')  return false
      }
      if (query && !line.toLowerCase().includes(query)) return false
      return true
    })
  }, [displayLogs, searchQuery, levelFilter])

  const isFiltered = searchQuery.trim() !== '' || levelFilter !== 'all'

  // Auto-scroll to bottom on new logs, unless user has scrolled up — only when not filtered
  useEffect(() => {
    if (isFiltered) return
    const container = containerRef.current
    if (!container) return
    const isNearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80
    if (isNearBottom) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [logs, isFiltered])

  // Scroll to bottom when the panel becomes visible (tab switch). Logs may have
  // accumulated while the panel was hidden — the effect above won't re-fire
  // because `logs` hasn't changed, so we scroll unconditionally on reveal.
  useEffect(() => {
    if (!visible || isFiltered) return
    bottomRef.current?.scrollIntoView({ behavior: 'instant' })
  }, [visible, isFiltered])

  const handleDownload = () => {
    const content = displayLogs.join('\n')
    const blob = new Blob([content], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `run-log-${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.txt`
    a.click()
    URL.revokeObjectURL(url)
  }

  const handleCopyAll = async () => {
    await navigator.clipboard.writeText(displayLogs.join('\n'))
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  // Auto-reset confirmClear after 3s if user doesn't confirm
  useEffect(() => {
    if (!confirmClear) return
    const id = setTimeout(() => setConfirmClear(false), 3000)
    return () => clearTimeout(id)
  }, [confirmClear])

  const clearSearch = () => {
    setSearchQuery('')
    setLevelFilter('all')
  }

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden flex flex-col min-h-[400px] max-h-[600px]">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-slate-800 flex-shrink-0">
        <div className="flex items-center gap-2">
          <Terminal className="w-4 h-4 text-slate-400" />
          <h2 className="text-sm font-semibold text-slate-200">Логи</h2>
          <span className="text-xs text-slate-600">
            {isFiltered
              ? `${filteredLogs.length} из ${displayLogs.length}`
              : `${displayLogs.length} записей`}
          </span>
        </div>
        <div className="flex items-center gap-1">
          {displayLogs.length > 0 && (
            <>
              <button
                type="button"
                onClick={handleCopyAll}
                title="Копировать все логи"
                className="p-1.5 rounded text-slate-600 hover:text-slate-300 hover:bg-slate-800 transition-colors"
              >
                {copied
                  ? <Check className="w-3.5 h-3.5 text-green-400" />
                  : <ClipboardCopy className="w-3.5 h-3.5" />
                }
              </button>
              <button
                type="button"
                onClick={handleDownload}
                title="Скачать лог"
                className="p-1.5 rounded text-slate-600 hover:text-slate-300 hover:bg-slate-800 transition-colors"
              >
                <Download className="w-3.5 h-3.5" />
              </button>
              {onClear && (
                confirmClear ? (
                  <button
                    type="button"
                    onClick={() => { onClear(); setConfirmClear(false) }}
                    title="Нажмите ещё раз для подтверждения"
                    className="p-1.5 rounded text-red-400 bg-slate-800 transition-colors text-xs font-medium"
                  >
                    Очистить?
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => setConfirmClear(true)}
                    title="Очистить лог"
                    className="p-1.5 rounded text-slate-600 hover:text-red-400 hover:bg-slate-800 transition-colors"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                )
              )}
            </>
          )}
        </div>
      </div>

      {/* Search + Level Filter toolbar */}
      <div className="flex items-center gap-2 px-4 py-2.5 border-b border-slate-800/60 flex-shrink-0">
        {/* Search input */}
        <div className="relative flex-1 min-w-0">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-slate-500 pointer-events-none" />
          <input
            type="text"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="Поиск по логам..."
            className="w-full bg-slate-800 border border-slate-700 rounded-md pl-8 pr-7 py-1.5 text-xs text-slate-300 placeholder-slate-600 focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-transparent"
          />
          {searchQuery && (
            <button
              type="button"
              onClick={() => setSearchQuery('')}
              className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-300 transition-colors"
            >
              <X className="w-3 h-3" />
            </button>
          )}
        </div>

        {/* Level filter pills */}
        <div className="flex items-center gap-1 flex-shrink-0">
          {LEVEL_OPTIONS.map(opt => (
            <button
              key={opt.key}
              type="button"
              onClick={() => setLevelFilter(opt.key)}
              className={
                levelFilter === opt.key
                  ? `text-xs px-2 py-1 rounded-md font-medium bg-slate-700 border border-slate-600 ${opt.textClass}`
                  : 'text-xs px-2 py-1 rounded-md text-slate-600 hover:text-slate-400 border border-transparent transition-colors'
              }
            >
              {opt.label}
            </button>
          ))}
        </div>

        {/* Clear filters button — only when filters are active */}
        {isFiltered && (
          <button
            type="button"
            onClick={clearSearch}
            title="Clear filters"
            className="flex-shrink-0 text-xs text-slate-500 hover:text-slate-300 transition-colors"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {/* Log output */}
      <div
        ref={containerRef}
        className="flex-1 overflow-y-auto px-4 py-3 font-mono text-xs leading-relaxed space-y-0.5"
      >
        {displayLogs.length === 0 ? (
          <p className="text-slate-600 italic">Ожидание событий...</p>
        ) : filteredLogs.length === 0 ? (
          <p className="text-slate-600 italic">Нет записей по текущему фильтру.</p>
        ) : (
          filteredLogs.map((line, i) => {
            const level = classifyLine(line)
            // Highlight the search match within the line
            if (searchQuery.trim()) {
              const query = searchQuery.trim()
              const idx = line.toLowerCase().indexOf(query.toLowerCase())
              if (idx !== -1) {
                return (
                  <div key={i} className={levelTextClass(level)}>
                    {line.slice(0, idx)}
                    <mark className="bg-yellow-400/30 text-yellow-200 rounded-sm">
                      {line.slice(idx, idx + query.length)}
                    </mark>
                    {line.slice(idx + query.length)}
                  </div>
                )
              }
            }
            return (
              <div key={i} className={levelTextClass(level)}>
                {line}
              </div>
            )
          })
        )}
        {/* Anchor for auto-scroll — only rendered when not filtered so scroll position isn't forced */}
        {!isFiltered && <div ref={bottomRef} />}
      </div>
    </div>
  )
}

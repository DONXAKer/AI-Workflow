import { useEffect, useState } from 'react'
import { AlertCircle } from 'lucide-react'
import { BlockConfigDto } from '../../../types'

interface Props {
  block: BlockConfigDto
  onChange: (config: Record<string, unknown>) => void
}

/**
 * Last-resort editor for blocks without a known FieldSchema. Shows a Monaco-style
 * raw-JSON textarea — the user edits the {@code block.config} map directly.
 */
export function RawJsonFallback({ block, onChange }: Props) {
  const [text, setText] = useState(() => JSON.stringify(block.config ?? {}, null, 2))
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    setText(JSON.stringify(block.config ?? {}, null, 2))
  }, [block.id, block.config])

  return (
    <div>
      <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
        Сырой config (JSON)
      </label>
      <textarea
        data-testid={`raw-json-${block.id}`}
        value={text}
        onChange={e => {
          const v = e.target.value
          setText(v)
          if (!v.trim()) {
            onChange({})
            setErr(null)
            return
          }
          try {
            const parsed = JSON.parse(v)
            if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
              setErr('JSON должен быть объектом')
              return
            }
            setErr(null)
            onChange(parsed as Record<string, unknown>)
          } catch (parseErr) {
            setErr(parseErr instanceof Error ? parseErr.message : 'Невалидный JSON')
          }
        }}
        rows={14}
        className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500 resize-y"
      />
      {err && (
        <div className="mt-1.5 flex items-center gap-1.5 text-xs text-red-400">
          <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" /> {err}
        </div>
      )}
      <p className="text-[10px] text-slate-600 mt-1">
        Метаданные блока не описаны — редактируйте конфиг как JSON.
      </p>
    </div>
  )
}

export default RawJsonFallback

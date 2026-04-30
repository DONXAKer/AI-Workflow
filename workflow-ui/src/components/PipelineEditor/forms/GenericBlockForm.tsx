import { useState } from 'react'
import { X } from 'lucide-react'
import { BlockConfigDto, FieldSchemaDto, PipelineConfigDto } from '../../../types'
import { HelpPopover, InterpolationHelpBody } from '../HelpPopover'

const NATIVE_TOOLS = ['Read', 'Write', 'Edit', 'Glob', 'Grep', 'Bash']

interface Props {
  block: BlockConfigDto
  fields: FieldSchemaDto[]
  config: PipelineConfigDto
  onChange: (config: Record<string, unknown>) => void
}

/**
 * Renders a form driven by a list of {@link FieldSchemaDto} entries. Used for blocks
 * that declare metadata but don't have a hand-written custom form (most of the
 * top-10).
 */
export function GenericBlockForm({ block, fields, config, onChange }: Props) {
  const cfg = (block.config ?? {}) as Record<string, unknown>

  const update = (name: string, value: unknown) => {
    const next = { ...cfg }
    if (value === undefined || value === null || value === '') {
      delete next[name]
    } else {
      next[name] = value
    }
    onChange(next)
  }

  return (
    <div className="space-y-4">
      {fields.map(field => (
        <FieldRow
          key={field.name}
          field={field}
          value={cfg[field.name]}
          onChange={v => update(field.name, v)}
          config={config}
          currentBlockId={block.id}
        />
      ))}
    </div>
  )
}

function FieldRow({
  field, value, onChange, config, currentBlockId,
}: {
  field: FieldSchemaDto
  value: unknown
  onChange: (v: unknown) => void
  config: PipelineConfigDto
  currentBlockId: string
}) {
  const id = `field-${field.name}`
  const hints = field.hints ?? {}
  // Show ${...}-syntax hint on text fields that look like templates: explicit
  // hint, or any multiline/monospace string (the "code-like" fields).
  const showInterpolationHelp = field.type === 'string' && (
    hints.interpolatable === true || hints.multiline === true || hints.monospace === true
  )
  return (
    <div>
      <label htmlFor={id} className="text-xs font-medium text-slate-300 mb-1 flex items-center gap-1.5">
        <span>
          {field.label}{' '}
          {field.required && <span className="text-red-400">*</span>}
        </span>
        {showInterpolationHelp && (
          <HelpPopover testId={`field-${field.name}`} title="Подстановка значений">
            <InterpolationHelpBody />
          </HelpPopover>
        )}
      </label>
      <FieldControl
        id={id}
        field={field}
        value={value}
        onChange={onChange}
        config={config}
        currentBlockId={currentBlockId}
      />
      {field.description && (
        <p className="text-[10px] text-slate-500 mt-1">{field.description}</p>
      )}
    </div>
  )
}

function FieldControl({
  id, field, value, onChange, config, currentBlockId,
}: {
  id: string
  field: FieldSchemaDto
  value: unknown
  onChange: (v: unknown) => void
  config: PipelineConfigDto
  currentBlockId: string
}) {
  const hints = field.hints ?? {}
  const monospace = hints.monospace === true
  const multiline = hints.multiline === true

  switch (field.type) {
    case 'string': {
      const s = (value as string | undefined) ?? ''
      if (multiline) {
        return (
          <textarea
            id={id}
            data-testid={id}
            value={s}
            onChange={e => onChange(e.target.value)}
            rows={6}
            className={`w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 resize-y ${monospace ? 'font-mono text-xs' : ''}`}
          />
        )
      }
      return (
        <input
          id={id}
          data-testid={id}
          type="text"
          value={s}
          onChange={e => onChange(e.target.value)}
          className={`w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 ${monospace ? 'font-mono text-xs' : ''}`}
        />
      )
    }
    case 'number': {
      const n = value as number | undefined
      const placeholder = field.defaultValue !== undefined && field.defaultValue !== null
        ? String(field.defaultValue) : ''
      return (
        <input
          id={id}
          data-testid={id}
          type="number"
          value={n ?? ''}
          placeholder={placeholder}
          onChange={e => {
            const v = e.target.value
            onChange(v === '' ? undefined : Number(v))
          }}
          className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
      )
    }
    case 'boolean': {
      const b = !!value
      return (
        <label className="flex items-center gap-2 text-sm text-slate-300 cursor-pointer">
          <input
            id={id}
            data-testid={id}
            type="checkbox"
            checked={b}
            onChange={e => onChange(e.target.checked)}
            className="accent-blue-500 w-4 h-4"
          />
          <span className="text-xs text-slate-500">{b ? 'true' : 'false'}</span>
        </label>
      )
    }
    case 'enum': {
      const values = ((field.hints?.values as string[]) ?? [])
      return (
        <select
          id={id}
          data-testid={id}
          value={(value as string | undefined) ?? ''}
          onChange={e => onChange(e.target.value || undefined)}
          className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
        >
          <option value="">— Не задано —</option>
          {values.map(v => <option key={v} value={v}>{v}</option>)}
        </select>
      )
    }
    case 'block_ref': {
      const ids = (config.pipeline ?? []).map(b => b.id).filter(Boolean)
      return (
        <select
          id={id}
          data-testid={id}
          value={(value as string | undefined) ?? ''}
          onChange={e => onChange(e.target.value || undefined)}
          className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
        >
          <option value="">— Не задано —</option>
          {ids.filter(i => i !== currentBlockId).map(i => (
            <option key={i} value={i}>{i}</option>
          ))}
        </select>
      )
    }
    case 'string_array': {
      return <ChipInput id={id} value={value as string[] | undefined} onChange={onChange} />
    }
    case 'tool_list': {
      const arr = (value as string[] | undefined) ?? []
      return (
        <div data-testid={id} className="grid grid-cols-2 gap-1.5">
          {NATIVE_TOOLS.map(t => (
            <label key={t} className="flex items-center gap-1.5 text-xs text-slate-300 cursor-pointer">
              <input
                type="checkbox"
                checked={arr.includes(t)}
                onChange={e => {
                  const set = new Set(arr)
                  if (e.target.checked) set.add(t); else set.delete(t)
                  onChange(Array.from(set))
                }}
                className="accent-blue-500 w-3.5 h-3.5"
              />
              <span className="font-mono">{t}</span>
            </label>
          ))}
        </div>
      )
    }
    default:
      return <span className="text-xs text-red-400">Неизвестный тип поля: {field.type}</span>
  }
}

function ChipInput({ id, value, onChange }: { id: string; value: string[] | undefined; onChange: (v: string[]) => void }) {
  const [draft, setDraft] = useState('')
  const arr = value ?? []
  return (
    <div data-testid={id} className="bg-slate-950 border border-slate-700 rounded-lg px-2 py-1.5">
      <div className="flex flex-wrap gap-1">
        {arr.map((c, i) => (
          <span key={i} className="bg-slate-800 text-slate-200 text-xs px-2 py-0.5 rounded flex items-center gap-1 font-mono">
            {c}
            <button type="button" onClick={() => onChange(arr.filter((_, idx) => idx !== i))} className="hover:text-red-400">
              <X className="w-3 h-3" />
            </button>
          </span>
        ))}
        <input
          type="text"
          value={draft}
          onChange={e => setDraft(e.target.value)}
          onKeyDown={e => {
            if (e.key === 'Enter' && draft.trim()) {
              e.preventDefault()
              onChange([...arr, draft.trim()])
              setDraft('')
            } else if (e.key === 'Backspace' && !draft && arr.length > 0) {
              onChange(arr.slice(0, -1))
            }
          }}
          onBlur={() => {
            if (draft.trim()) {
              onChange([...arr, draft.trim()])
              setDraft('')
            }
          }}
          placeholder="Добавить..."
          className="flex-1 min-w-[80px] bg-transparent text-sm text-slate-100 focus:outline-none font-mono text-xs"
        />
      </div>
    </div>
  )
}

export default GenericBlockForm

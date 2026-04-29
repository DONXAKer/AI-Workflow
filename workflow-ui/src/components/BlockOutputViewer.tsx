import { useState, type ReactNode } from 'react'
import { ChevronRight, ChevronDown, FileCode, FilePlus, Trash2, FileEdit } from 'lucide-react'
import clsx from 'clsx'
import ReactMarkdown from 'react-markdown'

interface FileChange {
  file_path?: string
  path?: string
  file?: string
  action?: string
  content?: string
  description?: string
}

const ACTION_BADGE: Record<string, { label: string; cls: string; icon: ReactNode }> = {
  create:  { label: 'создан',   cls: 'bg-green-900/60 text-green-300 border-green-800',  icon: <FilePlus  className="w-3 h-3" /> },
  modify:  { label: 'изменён',  cls: 'bg-blue-900/60  text-blue-300  border-blue-800',   icon: <FileEdit  className="w-3 h-3" /> },
  delete:  { label: 'удалён',   cls: 'bg-red-900/60   text-red-300   border-red-800',    icon: <Trash2    className="w-3 h-3" /> },
}

function actionBadge(action?: string) {
  const a = (action ?? '').toLowerCase()
  const cfg = ACTION_BADGE[a] ?? { label: a || '?', cls: 'bg-slate-800 text-slate-400 border-slate-700', icon: <FileCode className="w-3 h-3" /> }
  return (
    <span className={clsx('inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium border', cfg.cls)}>
      {cfg.icon}{cfg.label}
    </span>
  )
}

function FileRow({ file }: { file: FileChange }) {
  const [open, setOpen] = useState(false)
  const filePath = file.file_path ?? file.path ?? file.file ?? '(unknown)'
  const hasContent = !!file.content

  return (
    <div className="border border-slate-700/60 rounded-lg overflow-hidden">
      <button
        type="button"
        onClick={() => hasContent && setOpen(o => !o)}
        className={clsx(
          'w-full flex items-center gap-2 px-3 py-2 text-left text-sm',
          hasContent ? 'hover:bg-slate-800/60 cursor-pointer' : 'cursor-default',
          'bg-slate-900/80'
        )}
      >
        {hasContent ? (
          open ? <ChevronDown className="w-3.5 h-3.5 text-slate-500 flex-shrink-0" /> : <ChevronRight className="w-3.5 h-3.5 text-slate-500 flex-shrink-0" />
        ) : (
          <span className="w-3.5 h-3.5 flex-shrink-0" />
        )}
        <FileCode className="w-3.5 h-3.5 text-slate-400 flex-shrink-0" />
        <span className="font-mono text-slate-200 text-xs truncate flex-1">{filePath}</span>
        {actionBadge(file.action)}
      </button>

      {file.description && !open && (
        <p className="px-9 pb-1.5 text-xs text-slate-500">{file.description}</p>
      )}

      {open && hasContent && (
        <div className="border-t border-slate-700/60">
          {file.description && (
            <p className="px-3 py-1.5 text-xs text-slate-400 bg-slate-900/60 border-b border-slate-700/40">{file.description}</p>
          )}
          <pre className="px-3 py-2.5 text-[11px] font-mono text-slate-300 overflow-auto max-h-64 bg-slate-950 whitespace-pre-wrap">
            {file.content}
          </pre>
        </div>
      )}
    </div>
  )
}

function FileGroup({ label, files }: { label: string; files: FileChange[] }) {
  const [open, setOpen] = useState(false)
  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="flex items-center gap-1.5 text-xs font-medium text-slate-400 hover:text-slate-200 transition-colors mb-1.5"
      >
        {open ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
        {label}
        <span className="ml-0.5 px-1.5 py-0.5 rounded-full bg-slate-700 text-slate-300 text-[10px]">{files.length}</span>
      </button>
      {open && (
        <div className="space-y-1.5 ml-1">
          {files.map((f, i) => <FileRow key={i} file={f} />)}
        </div>
      )}
    </div>
  )
}

/** Renders shell stdout/stderr with diff coloring and section headers. */
function ShellOutputField({ label, value }: { label: string; value: string }) {
  const [expanded, setExpanded] = useState(false)
  const lines = value.split('\n')
  const MAX_LINES = 100
  const shown = expanded ? lines : lines.slice(0, MAX_LINES)
  const truncated = !expanded && lines.length > MAX_LINES

  return (
    <div>
      <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-1">{label}</dt>
      <dd>
        <pre className="bg-slate-950 border border-slate-700/60 rounded-lg px-3 py-2.5 text-[11px] font-mono overflow-auto max-h-80 leading-relaxed">
          {shown.map((line, i) => {
            const isDiffAdd   = /^\+[^+]/.test(line)
            const isDiffDel   = /^-[^-]/.test(line)
            const isDiffHunk  = /^@@/.test(line)
            const isSection   = /^={3}.*={3}$/.test(line.trim())

            const cls =
              isDiffAdd  ? 'text-green-400 bg-green-950/30 block' :
              isDiffDel  ? 'text-red-400   bg-red-950/30   block' :
              isDiffHunk ? 'text-blue-400' :
              isSection  ? 'text-slate-300 font-semibold' :
              'text-slate-400'

            return <span key={i} className={cls}>{line}{'\n'}</span>
          })}
          {truncated && (
            <span className="text-slate-600">… ещё {lines.length - MAX_LINES} строк</span>
          )}
        </pre>
        {lines.length > MAX_LINES && (
          <button
            type="button"
            onClick={() => setExpanded(e => !e)}
            className="text-xs text-blue-400 hover:text-blue-300 mt-1"
          >
            {expanded ? 'Свернуть' : `Показать все ${lines.length} строк`}
          </button>
        )}
      </dd>
    </div>
  )
}

/** Fields whose values are expected to contain markdown prose. */
const MARKDOWN_KEYS = new Set([
  'summary', 'technical_approach', 'approach', 'recommendation',
  'goal', 'description', 'analysis', 'plan', 'review',
  'retry_instruction', 'carry_forward', 'issues', 'message',
  'definition_of_done', 'risk', 'risks',
])

function looksLikeMarkdown(value: string): boolean {
  return /^#{1,6}\s|^\*\*|\n#{1,6}\s|\n\*\s|\n-\s|\n\d+\.\s/.test(value)
}

function StringField({ label, value, fieldKey }: { label: string; value: string; fieldKey?: string }) {
  const [expanded, setExpanded] = useState(false)
  const long = value.length > 600
  const shown = long && !expanded ? value.slice(0, 600) + '…' : value
  const isMarkdown = (fieldKey && MARKDOWN_KEYS.has(fieldKey)) || looksLikeMarkdown(value)

  return (
    <div>
      <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-0.5">{label}</dt>
      {isMarkdown ? (
        <dd className="prose prose-invert prose-sm max-w-none text-slate-200 leading-relaxed
          [&_h1]:text-base [&_h1]:font-semibold [&_h1]:mt-2 [&_h1]:mb-1
          [&_h2]:text-sm [&_h2]:font-semibold [&_h2]:mt-2 [&_h2]:mb-1
          [&_h3]:text-sm [&_h3]:font-medium [&_h3]:mt-1.5 [&_h3]:mb-0.5
          [&_ul]:my-1 [&_ul]:pl-4 [&_li]:my-0.5
          [&_ol]:my-1 [&_ol]:pl-4
          [&_p]:my-1 [&_code]:bg-slate-800 [&_code]:px-1 [&_code]:rounded [&_code]:text-xs
          [&_pre]:bg-slate-950 [&_pre]:rounded [&_pre]:p-2 [&_pre]:text-xs [&_pre]:overflow-auto
          [&_strong]:text-white [&_em]:text-slate-300
          [&_blockquote]:border-l-2 [&_blockquote]:border-slate-600 [&_blockquote]:pl-3 [&_blockquote]:text-slate-400">
          <ReactMarkdown>{shown}</ReactMarkdown>
        </dd>
      ) : (
        <dd className="text-sm text-slate-200 font-mono whitespace-pre-wrap break-words leading-relaxed">{shown}</dd>
      )}
      {long && (
        <button type="button" onClick={() => setExpanded(e => !e)} className="text-xs text-blue-400 hover:text-blue-300 mt-0.5">
          {expanded ? 'Свернуть' : 'Показать полностью'}
        </button>
      )}
    </div>
  )
}

function ScalarField({ label, value }: { label: string; value: unknown }) {
  return (
    <div>
      <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-0.5">{label}</dt>
      <dd className="text-sm text-slate-200">{String(value)}</dd>
    </div>
  )
}

const FIELD_LABELS: Record<string, string> = {
  branch_name:        'Ветка',
  commit_message:     'Коммит',
  summary:            'Сводка',
  status:             'Статус',
  message:            'Сообщение',
  pr_url:             'URL PR',
  mr_url:             'URL MR',
  score:              'Оценка',
  issues:             'Проблемы',
  recommendation:     'Рекомендация',
  complexity:         'Сложность',
  technical_approach: 'Тех. подход',
  stdout:             'Вывод',
  stderr:             'Ошибки',
  exit_code:          'Код завершения',
  success:            'Успешно',
}

const FILE_ARRAY_KEYS = new Set(['changes', 'test_changes', 'file_changes', 'files'])
const FILE_ARRAY_LABELS: Record<string, string> = {
  changes:      'Изменённые файлы',
  test_changes: 'Тестовые файлы',
  file_changes: 'Изменённые файлы',
  files:        'Файлы',
}

function isFileChange(v: unknown): v is FileChange {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

const SHELL_OUTPUT_KEYS = new Set(['stdout', 'stderr', 'output'])

interface Props {
  output: Record<string, unknown>
}

export default function BlockOutputViewer({ output }: Props) {
  const entries = Object.entries(output)
  if (entries.length === 0) return <p className="text-sm text-slate-500">Выход пуст.</p>

  const fileGroups:    Array<{ key: string; files: FileChange[] }> = []
  const shellOutputs:  Array<{ key: string; value: string }>       = []
  const stringFields:  Array<{ key: string; value: string }>       = []
  const scalarFields:  Array<{ key: string; value: unknown }>      = []
  const unknownArrays: Array<{ key: string; value: unknown[] }>    = []

  for (const [key, value] of entries) {
    // Skip empty stderr
    if (key === 'stderr' && typeof value === 'string' && value.trim() === '') continue
    // Skip clean status fields (success=true, exit_code=0)
    if (key === 'success'   && value === true)  continue
    if (key === 'exit_code' && value === 0)     continue

    if (FILE_ARRAY_KEYS.has(key) && Array.isArray(value) && value.every(isFileChange)) {
      fileGroups.push({ key, files: value as FileChange[] })
    } else if (SHELL_OUTPUT_KEYS.has(key) && typeof value === 'string') {
      if (value.trim() !== '') shellOutputs.push({ key, value })
    } else if (typeof value === 'string') {
      stringFields.push({ key, value })
    } else if (typeof value === 'number' || typeof value === 'boolean') {
      scalarFields.push({ key, value })
    } else if (Array.isArray(value)) {
      unknownArrays.push({ key, value })
    } else if (value !== null && value !== undefined) {
      stringFields.push({ key, value: JSON.stringify(value, null, 2) })
    }
  }

  return (
    <dl className="space-y-3">
      {stringFields.map(({ key, value }) => (
        <StringField key={key} label={FIELD_LABELS[key] ?? key} value={value} fieldKey={key} />
      ))}
      {scalarFields.map(({ key, value }) => (
        <ScalarField key={key} label={FIELD_LABELS[key] ?? key} value={value} />
      ))}
      {fileGroups.map(({ key, files }) => (
        <FileGroup key={key} label={FILE_ARRAY_LABELS[key] ?? key} files={files} />
      ))}
      {shellOutputs.map(({ key, value }) => (
        <ShellOutputField key={key} label={FIELD_LABELS[key] ?? key} value={value} />
      ))}
      {unknownArrays.map(({ key, value }) => (
        <div key={key}>
          <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-0.5">{FIELD_LABELS[key] ?? key}</dt>
          <dd>
            <pre className="text-[11px] font-mono text-slate-300 bg-slate-950 border border-slate-700/60 rounded px-3 py-2 overflow-auto max-h-40 whitespace-pre-wrap">
              {JSON.stringify(value, null, 2)}
            </pre>
          </dd>
        </div>
      ))}
    </dl>
  )
}

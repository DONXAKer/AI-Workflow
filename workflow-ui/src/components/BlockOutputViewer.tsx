import { useState, type ReactNode } from 'react'
import { ChevronRight, ChevronDown, FileCode, FilePlus, Trash2, FileEdit, Check, X, AlertCircle, CircleDot } from 'lucide-react'
import clsx from 'clsx'
import ReactMarkdown from 'react-markdown'

// ─────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────
interface FileChange {
  file_path?: string; path?: string; file?: string
  action?: string; content?: string; description?: string
  insertions?: number; deletions?: number; status?: string
}

interface ChecklistItem {
  id: string; text?: string; passed?: boolean
  evidence?: string; fix?: string; priority?: string; source?: string
}

interface RequirementCoverage {
  requirement: string; approach: string; files?: string
}

interface BestPractice {
  rule: string; source?: string; confidence?: number
}

interface TechStack {
  language?: string; framework?: string; build_tool?: string; key_deps?: string[]
}

interface IssueInfo {
  id?: string; readableId?: string; summary?: string
  status?: string; url?: string; description?: string
}

// ─────────────────────────────────────────────────────────────────
// Russian labels for output field keys
// ─────────────────────────────────────────────────────────────────
const FIELD_LABELS: Record<string, string> = {
  // Common
  branch_name:              'Ветка',
  commit_message:           'Коммит',
  summary:                  'Сводка',
  status:                   'Статус',
  message:                  'Сообщение',
  pr_url:                   'URL PR',
  mr_url:                   'URL MR',
  score:                    'Оценка',
  issues:                   'Проблемы',
  recommendation:           'Рекомендация',
  complexity:               'Сложность',
  technical_approach:       'Технический подход',
  stdout:                   'Вывод',
  stderr:                   'Ошибки',
  exit_code:                'Код завершения',
  success:                  'Успешно',
  // Analysis
  requirement:              'Требование',
  affected_components:      'Затронутые компоненты',
  estimated_complexity:     'Оценка сложности',
  risks:                    'Риски',
  open_questions:           'Открытые вопросы',
  acceptance_checklist:     'Acceptance checklist',
  needs_clarification:      'Нужно уточнение',
  // Plan
  goal:                     'Цель',
  requirements_coverage:    'Покрытие требований',
  files_to_touch:           'Файлы для изменения',
  approach:                 'Подход',
  definition_of_done:       'Definition of Done',
  tools_to_use:             'Инструменты',
  mode:                     'Режим',
  iterations_used:          'Итераций',
  total_cost_usd:           'Стоимость',
  // Review / Orchestrator
  checklist_status:         'Статус checklist',
  regressions:              'Регрессии',
  carry_forward:            'Перенос контекста',
  retry_instruction:        'Инструкция для retry',
  passed:                   'Пройдено',
  action:                   'Действие',
  // Chunk / agent_with_tools
  final_text:               'Результат агента',
  files_changed:            'Изменённые файлы',
  diff_summary:             'Сводка изменений',
  // Context scan
  tech_stack:               'Технологический стек',
  code_conventions:         'Конвенции кода',
  applicable_best_practices:'Лучшие практики',
  suggestions_for_codegen:  'Рекомендации для кодогенерации',
  language:                 'Язык',
  source_files_sampled:     'Файлов просканировано',
  // Task input
  task_mode:                'Режим задачи',
  youtrack_source_issue:    'Исходная задача',
  issue:                    'Задача',
  provider:                 'Провайдер трекера',
}

// ─────────────────────────────────────────────────────────────────
// Keys to hide entirely (internal / debug)
// ─────────────────────────────────────────────────────────────────
const SKIP_KEYS = new Set([
  '_llm', 'total_input_tokens', 'total_output_tokens', 'tool_calls_made',
  'runtimeOverridesJson', 'skillsSnapshotJson',
])

// Keys whose values are markdown prose
const MARKDOWN_KEYS = new Set([
  'summary', 'technical_approach', 'approach', 'recommendation',
  'goal', 'description', 'analysis', 'plan', 'review',
  'retry_instruction', 'carry_forward', 'issues', 'message',
  'definition_of_done', 'risks', 'final_text',
])

function looksLikeMarkdown(v: string) {
  return /^#{1,6}\s|^\*\*|\n#{1,6}\s|\n\*\s|\n-\s|\n\d+\.\s/.test(v)
}

// ─────────────────────────────────────────────────────────────────
// Action badge (file status)
// ─────────────────────────────────────────────────────────────────
const ACTION_BADGE: Record<string, { label: string; cls: string; icon: ReactNode }> = {
  create:   { label: 'создан',  cls: 'bg-green-900/60 text-green-300 border-green-800',  icon: <FilePlus  className="w-3 h-3" /> },
  modified: { label: 'изменён', cls: 'bg-blue-900/60  text-blue-300  border-blue-800',   icon: <FileEdit  className="w-3 h-3" /> },
  modify:   { label: 'изменён', cls: 'bg-blue-900/60  text-blue-300  border-blue-800',   icon: <FileEdit  className="w-3 h-3" /> },
  delete:   { label: 'удалён',  cls: 'bg-red-900/60   text-red-300   border-red-800',    icon: <Trash2    className="w-3 h-3" /> },
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

// ─────────────────────────────────────────────────────────────────
// File row (collapsible with content)
// ─────────────────────────────────────────────────────────────────
function FileRow({ file }: { file: FileChange }) {
  const [open, setOpen] = useState(false)
  const filePath = file.file_path ?? file.path ?? file.file ?? '(unknown)'
  const hasContent = !!file.content
  return (
    <div className="border border-slate-700/60 rounded-lg overflow-hidden">
      <button type="button" onClick={() => hasContent && setOpen(o => !o)}
        className={clsx('w-full flex items-center gap-2 px-3 py-2 text-left text-sm bg-slate-900/80',
          hasContent ? 'hover:bg-slate-800/60 cursor-pointer' : 'cursor-default')}>
        {hasContent
          ? (open ? <ChevronDown className="w-3.5 h-3.5 text-slate-500 shrink-0" /> : <ChevronRight className="w-3.5 h-3.5 text-slate-500 shrink-0" />)
          : <span className="w-3.5 h-3.5 shrink-0" />}
        <FileCode className="w-3.5 h-3.5 text-slate-400 shrink-0" />
        <span className="font-mono text-slate-200 text-xs truncate flex-1">{filePath}</span>
        {file.insertions != null && (
          <span className="text-[10px] font-mono text-green-400">+{file.insertions}</span>
        )}
        {file.deletions != null && (
          <span className="text-[10px] font-mono text-red-400 ml-1">-{file.deletions}</span>
        )}
        {actionBadge(file.action ?? file.status)}
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
      <button type="button" onClick={() => setOpen(o => !o)}
        className="flex items-center gap-1.5 text-xs font-medium text-slate-400 hover:text-slate-200 transition-colors mb-1.5">
        {open ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
        {label}
        <span className="ml-0.5 px-1.5 py-0.5 rounded-full bg-slate-700 text-slate-300 text-[10px]">{files.length}</span>
      </button>
      {open && <div className="space-y-1.5 ml-1">{files.map((f, i) => <FileRow key={i} file={f} />)}</div>}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────
// Checklist renderer (acceptance_checklist + checklist_status)
// ─────────────────────────────────────────────────────────────────
function ChecklistField({ label, items, isStatus }: { label: string; items: ChecklistItem[]; isStatus?: boolean }) {
  const [open, setOpen] = useState(true)
  if (items.length === 0) return null
  const passedCount = isStatus ? items.filter(i => i.passed).length : null
  return (
    <div>
      <button type="button" onClick={() => setOpen(o => !o)}
        className="flex items-center gap-1.5 text-[10px] font-medium text-slate-500 uppercase tracking-wide hover:text-slate-300 transition-colors mb-1.5">
        {open ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
        {label}
        {passedCount !== null && (
          <span className={clsx('px-1.5 py-0.5 rounded-full text-[10px] font-medium',
            passedCount === items.length ? 'bg-emerald-900/50 text-emerald-300' : 'bg-amber-900/50 text-amber-300')}>
            {passedCount}/{items.length}
          </span>
        )}
        {passedCount === null && (
          <span className="px-1.5 py-0.5 rounded-full bg-slate-700 text-slate-300 text-[10px]">{items.length}</span>
        )}
      </button>
      {open && (
        <ul className="space-y-2 ml-1">
          {items.map((item, i) => (
            <li key={item.id ?? i} className={clsx(
              'border rounded-lg px-3 py-2 text-sm',
              isStatus
                ? (item.passed ? 'border-emerald-800/60 bg-emerald-900/10' : 'border-red-800/60 bg-red-900/10')
                : 'border-slate-700/60 bg-slate-900/40',
            )}>
              <div className="flex items-start gap-2">
                {isStatus && (
                  item.passed
                    ? <Check className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
                    : <X className="w-4 h-4 text-red-400 shrink-0 mt-0.5" />
                )}
                {!isStatus && item.priority && (
                  <span className={clsx('text-[10px] px-1.5 py-0.5 rounded border shrink-0 mt-0.5 font-medium',
                    item.priority === 'critical'  ? 'border-red-700 bg-red-900/30 text-red-300' :
                    item.priority === 'important' ? 'border-amber-700 bg-amber-900/30 text-amber-300' :
                    'border-slate-600 bg-slate-800 text-slate-400')}>
                    {item.priority}
                  </span>
                )}
                <span className="text-slate-200 leading-relaxed">{item.text ?? item.id}</span>
              </div>
              {isStatus && item.evidence && (
                <p className="text-xs text-slate-400 mt-1 ml-6 leading-relaxed">{item.evidence}</p>
              )}
              {isStatus && !item.passed && item.fix && (
                <p className="text-xs text-amber-300/80 mt-1 ml-6 leading-relaxed">
                  <span className="font-medium">Исправление: </span>{item.fix}
                </p>
              )}
              {!isStatus && item.id && (
                <p className="text-[10px] font-mono text-slate-600 mt-0.5 ml-6">{item.id}</p>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────
// Bullet list renderer (open_questions, risks, affected_components, …)
// ─────────────────────────────────────────────────────────────────
function BulletListField({ label, items, icon }: { label: string; items: string[]; icon?: ReactNode }) {
  if (items.length === 0) return null
  return (
    <div>
      <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-1">{label}</dt>
      <ul className="space-y-1">
        {items.map((item, i) => (
          <li key={i} className="flex items-start gap-2 text-sm text-slate-200">
            <span className="text-slate-500 shrink-0 mt-0.5">{icon ?? <CircleDot className="w-3 h-3" />}</span>
            <span className="leading-relaxed">{item}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────
// Requirements coverage
// ─────────────────────────────────────────────────────────────────
function RequirementsCoverageField({ label, items }: { label: string; items: RequirementCoverage[] }) {
  const [open, setOpen] = useState(false)
  if (items.length === 0) return null
  return (
    <div>
      <button type="button" onClick={() => setOpen(o => !o)}
        className="flex items-center gap-1.5 text-[10px] font-medium text-slate-500 uppercase tracking-wide hover:text-slate-300 transition-colors mb-1.5">
        {open ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
        {label}
        <span className="px-1.5 py-0.5 rounded-full bg-slate-700 text-slate-300 text-[10px]">{items.length}</span>
      </button>
      {open && (
        <ul className="space-y-2 ml-1">
          {items.map((item, i) => (
            <li key={i} className="border border-slate-700/60 rounded-lg px-3 py-2 bg-slate-900/40">
              <p className="text-xs text-slate-400 leading-relaxed">{item.requirement}</p>
              <p className="text-xs text-slate-200 mt-1 leading-relaxed">{item.approach}</p>
              {item.files && (
                <p className="text-[10px] font-mono text-slate-500 mt-0.5">{item.files}</p>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────
// Best practices renderer
// ─────────────────────────────────────────────────────────────────
function BestPracticesField({ label, items }: { label: string; items: BestPractice[] }) {
  const [open, setOpen] = useState(false)
  if (items.length === 0) return null
  return (
    <div>
      <button type="button" onClick={() => setOpen(o => !o)}
        className="flex items-center gap-1.5 text-[10px] font-medium text-slate-500 uppercase tracking-wide hover:text-slate-300 transition-colors mb-1.5">
        {open ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
        {label}
        <span className="px-1.5 py-0.5 rounded-full bg-slate-700 text-slate-300 text-[10px]">{items.length}</span>
      </button>
      {open && (
        <ul className="space-y-1.5 ml-1">
          {items.map((p, i) => (
            <li key={i} className="text-xs text-slate-300 flex items-start gap-2">
              <CircleDot className="w-3 h-3 text-slate-500 shrink-0 mt-0.5" />
              <span className="leading-relaxed">
                {p.rule}
                {p.source && <span className="text-slate-500 ml-1">({p.source})</span>}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────
// Tech stack renderer
// ─────────────────────────────────────────────────────────────────
function TechStackField({ label, stack }: { label: string; stack: TechStack }) {
  const pills = [
    stack.language   && { label: stack.language,   cls: 'bg-blue-900/40 text-blue-300 border-blue-800' },
    stack.framework  && { label: stack.framework,  cls: 'bg-purple-900/40 text-purple-300 border-purple-800' },
    stack.build_tool && { label: stack.build_tool, cls: 'bg-slate-800 text-slate-300 border-slate-700' },
    ...(stack.key_deps ?? []).map(d => ({ label: d, cls: 'bg-slate-800/60 text-slate-400 border-slate-700' })),
  ].filter(Boolean) as Array<{ label: string; cls: string }>

  return (
    <div>
      <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-1">{label}</dt>
      <dd className="flex flex-wrap gap-1.5">
        {pills.map((p, i) => (
          <span key={i} className={clsx('text-xs px-2 py-0.5 rounded-full border', p.cls)}>{p.label}</span>
        ))}
      </dd>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────
// Issue card renderer
// ─────────────────────────────────────────────────────────────────
function IssueField({ label, issue }: { label: string; issue: IssueInfo }) {
  return (
    <div>
      <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-1">{label}</dt>
      <dd className="border border-slate-700/60 rounded-lg px-3 py-2 bg-slate-900/40 text-sm">
        {issue.readableId && (
          <span className="font-mono text-xs text-blue-400 mr-2">{issue.readableId}</span>
        )}
        {issue.summary && <span className="text-slate-200">{issue.summary}</span>}
        {issue.status && (
          <span className="ml-2 text-xs px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 border border-slate-700">{issue.status}</span>
        )}
        {issue.url && (
          <p className="text-[10px] font-mono text-slate-600 mt-0.5 truncate">{issue.url}</p>
        )}
      </dd>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────
// Shell output (diff coloring)
// ─────────────────────────────────────────────────────────────────
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
            const isDiffAdd  = /^\+[^+]/.test(line)
            const isDiffDel  = /^-[^-]/.test(line)
            const isDiffHunk = /^@@/.test(line)
            const isSection  = /^={3}.*={3}$/.test(line.trim())
            const cls = isDiffAdd ? 'text-green-400 bg-green-950/30 block'
              : isDiffDel ? 'text-red-400 bg-red-950/30 block'
              : isDiffHunk ? 'text-blue-400'
              : isSection ? 'text-slate-300 font-semibold'
              : 'text-slate-400'
            return <span key={i} className={cls}>{line}{'\n'}</span>
          })}
          {truncated && <span className="text-slate-600">… ещё {lines.length - MAX_LINES} строк</span>}
        </pre>
        {lines.length > MAX_LINES && (
          <button type="button" onClick={() => setExpanded(e => !e)} className="text-xs text-blue-400 hover:text-blue-300 mt-1">
            {expanded ? 'Свернуть' : `Показать все ${lines.length} строк`}
          </button>
        )}
      </dd>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────
// Generic string field (markdown or mono)
// ─────────────────────────────────────────────────────────────────
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
          [&_ul]:my-1 [&_ul]:pl-4 [&_li]:my-0.5 [&_ol]:my-1 [&_ol]:pl-4 [&_p]:my-1
          [&_code]:bg-slate-800 [&_code]:px-1 [&_code]:rounded [&_code]:text-xs
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

// ─────────────────────────────────────────────────────────────────
// Scalar field
// ─────────────────────────────────────────────────────────────────
function ScalarField({ label, value, fieldKey }: { label: string; value: unknown; fieldKey?: string }) {
  // Boolean badge
  if (typeof value === 'boolean') {
    return (
      <div>
        <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-0.5">{label}</dt>
        <dd>
          <span className={clsx('inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full border font-medium',
            value ? 'bg-emerald-900/40 text-emerald-300 border-emerald-700' : 'bg-red-900/40 text-red-300 border-red-700')}>
            {value ? <Check className="w-3 h-3" /> : <X className="w-3 h-3" />}
            {value ? 'да' : 'нет'}
          </span>
        </dd>
      </div>
    )
  }
  // Cost formatting
  if (fieldKey === 'total_cost_usd' && typeof value === 'number') {
    return (
      <div>
        <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-0.5">{label}</dt>
        <dd className="text-sm text-slate-200 font-mono">${value.toFixed(5)}</dd>
      </div>
    )
  }
  // Complexity with color
  if (fieldKey === 'estimated_complexity' && typeof value === 'string') {
    const cls = value === 'high' ? 'text-red-400' : value === 'medium' ? 'text-amber-400' : 'text-emerald-400'
    return (
      <div>
        <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-0.5">{label}</dt>
        <dd className={clsx('text-sm font-medium', cls)}>{value}</dd>
      </div>
    )
  }
  return (
    <div>
      <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-0.5">{label}</dt>
      <dd className="text-sm text-slate-200">{String(value)}</dd>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────
// Main component
// ─────────────────────────────────────────────────────────────────
const FILE_ARRAY_KEYS  = new Set(['changes', 'test_changes', 'file_changes', 'files', 'files_changed'])
const FILE_ARRAY_LABELS: Record<string, string> = {
  changes:      'Изменённые файлы',
  test_changes: 'Тестовые файлы',
  file_changes: 'Изменённые файлы',
  files:        'Файлы',
  files_changed:'Изменённые файлы',
}
const SHELL_OUTPUT_KEYS = new Set(['stdout', 'stderr', 'output'])

function isFileChange(v: unknown): v is FileChange {
  return typeof v === 'object' && v !== null && !Array.isArray(v) &&
    ('file_path' in v || 'path' in v || 'file' in v)
}
function isChecklistItem(v: unknown): v is ChecklistItem {
  return typeof v === 'object' && v !== null && !Array.isArray(v) && 'id' in v
}
function isRequirementCoverage(v: unknown): v is RequirementCoverage {
  return typeof v === 'object' && v !== null && !Array.isArray(v) && 'requirement' in v
}
function isBestPractice(v: unknown): v is BestPractice {
  return typeof v === 'object' && v !== null && !Array.isArray(v) && 'rule' in v
}

interface Props { output: Record<string, unknown> }

export default function BlockOutputViewer({ output }: Props) {
  const entries = Object.entries(output)
  if (entries.length === 0) return <p className="text-sm text-slate-500">Выход пуст.</p>

  const rendered: ReactNode[] = []

  for (const [key, value] of entries) {
    if (SKIP_KEYS.has(key)) continue
    if (key.startsWith('_')) continue
    if (key === 'stderr' && typeof value === 'string' && value.trim() === '') continue
    if (key === 'success' && value === true) continue
    if (key === 'exit_code' && value === 0) continue

    const label = FIELD_LABELS[key] ?? key

    // ── file array ──────────────────────────────────────────────
    if (FILE_ARRAY_KEYS.has(key) && Array.isArray(value) && value.every(isFileChange)) {
      rendered.push(
        <FileGroup key={key} label={FILE_ARRAY_LABELS[key] ?? label} files={value as FileChange[]} />
      )
      continue
    }

    // ── checklist / status arrays ──────────────────────────────
    if ((key === 'acceptance_checklist' || key === 'checklist_status') &&
        Array.isArray(value) && value.every(isChecklistItem)) {
      rendered.push(
        <ChecklistField key={key} label={label} items={value as ChecklistItem[]} isStatus={key === 'checklist_status'} />
      )
      continue
    }

    // ── regressions (checklist-like) ───────────────────────────
    if (key === 'regressions' && Array.isArray(value)) {
      if (value.length === 0) {
        rendered.push(
          <div key={key}>
            <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-0.5">{label}</dt>
            <dd className="inline-flex items-center gap-1 text-xs text-emerald-400">
              <Check className="w-3.5 h-3.5" /> Регрессий не обнаружено
            </dd>
          </div>
        )
      } else {
        rendered.push(
          <ChecklistField key={key} label={label} items={value as ChecklistItem[]} isStatus />
        )
      }
      continue
    }

    // ── requirements coverage ──────────────────────────────────
    if (key === 'requirements_coverage' && Array.isArray(value) && value.every(isRequirementCoverage)) {
      rendered.push(
        <RequirementsCoverageField key={key} label={label} items={value as RequirementCoverage[]} />
      )
      continue
    }

    // ── best practices ─────────────────────────────────────────
    if ((key === 'applicable_best_practices') && Array.isArray(value) && value.every(isBestPractice)) {
      rendered.push(
        <BestPracticesField key={key} label={label} items={value as BestPractice[]} />
      )
      continue
    }

    // ── string arrays → bullet list ────────────────────────────
    if (Array.isArray(value) && value.every(v => typeof v === 'string')) {
      const strs = value as string[]
      if (strs.length === 0) continue
      const icon = key === 'risks' ? <AlertCircle className="w-3 h-3 text-amber-500 shrink-0" /> : undefined
      rendered.push(
        <BulletListField key={key} label={label} items={strs} icon={icon} />
      )
      continue
    }

    // ── tech stack ─────────────────────────────────────────────
    if (key === 'tech_stack' && typeof value === 'object' && value !== null && !Array.isArray(value)) {
      rendered.push(
        <TechStackField key={key} label={label} stack={value as TechStack} />
      )
      continue
    }

    // ── issue / youtrack_source_issue ──────────────────────────
    if ((key === 'issue' || key === 'youtrack_source_issue') && typeof value === 'object' && value !== null && !Array.isArray(value)) {
      const iss = value as IssueInfo
      if (iss.readableId || iss.summary || iss.id) {
        rendered.push(<IssueField key={key} label={label} issue={iss} />)
        continue
      }
    }

    // ── shell stdout/stderr ────────────────────────────────────
    if (SHELL_OUTPUT_KEYS.has(key) && typeof value === 'string' && value.trim()) {
      rendered.push(<ShellOutputField key={key} label={label} value={value} />)
      continue
    }

    // ── string ────────────────────────────────────────────────
    if (typeof value === 'string') {
      if (value.trim() === '') continue
      rendered.push(<StringField key={key} label={label} value={value} fieldKey={key} />)
      continue
    }

    // ── number / boolean ──────────────────────────────────────
    if (typeof value === 'number' || typeof value === 'boolean') {
      rendered.push(<ScalarField key={key} label={label} value={value} fieldKey={key} />)
      continue
    }

    // ── unknown array / object — compact JSON ─────────────────
    if (value !== null && value !== undefined) {
      const json = JSON.stringify(value, null, 2)
      if (json === '[]' || json === '{}') continue
      rendered.push(
        <div key={key}>
          <dt className="text-[10px] font-medium text-slate-500 uppercase tracking-wide mb-0.5">{label}</dt>
          <dd>
            <pre className="text-[11px] font-mono text-slate-300 bg-slate-950 border border-slate-700/60 rounded px-3 py-2 overflow-auto max-h-40 whitespace-pre-wrap">
              {json}
            </pre>
          </dd>
        </div>
      )
    }
  }

  return <dl className="space-y-3">{rendered}</dl>
}

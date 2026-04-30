import { useEffect, useRef, useState, ReactNode } from 'react'
import { HelpCircle } from 'lucide-react'

interface Props {
  children: ReactNode
  title?: string
  /** Test id stem for the trigger button (suffix `-help`) and the popover (suffix `-popover`). */
  testId?: string
}

/**
 * Small (?) icon that toggles an inline popover with a syntax cheatsheet.
 * Click outside closes. Use inline next to a label for a discoverable hint.
 */
export function HelpPopover({ children, title, testId }: Props) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    // Capture phase: don't let an inner mousedown handler swallow the close trigger.
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', h, true)
    document.addEventListener('keydown', k)
    return () => {
      document.removeEventListener('mousedown', h, true)
      document.removeEventListener('keydown', k)
    }
  }, [open])

  return (
    <span className="relative inline-block align-middle" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="text-slate-500 hover:text-blue-400 inline-flex items-center opacity-60 hover:opacity-100"
        data-testid={testId ? `${testId}-help` : undefined}
        aria-label={title ?? 'Подсказка'}
        aria-expanded={open}
      >
        <HelpCircle className="w-3.5 h-3.5" />
      </button>
      {open && (
        <div
          data-testid={testId ? `${testId}-popover` : undefined}
          className="absolute z-40 top-5 left-0 w-72 bg-slate-900 border border-slate-700 rounded-lg shadow-xl p-3 text-xs text-slate-200 leading-relaxed"
        >
          {title && <div className="font-semibold text-slate-100 mb-1.5">{title}</div>}
          {children}
        </div>
      )}
    </span>
  )
}

/** Cheatsheet body for the `${block.field}` interpolation form (string templates). */
export function InterpolationHelpBody() {
  return (
    <div className="space-y-2">
      <p>Подставляет значение в строку. Резолвится из output ранее выполненных блоков.</p>
      <ul className="space-y-1 list-disc pl-4">
        <li>
          <code className="font-mono bg-slate-950 px-1 rounded">{'${block_id.field}'}</code>{' '}
          — поле из output блока
        </li>
        <li>
          <code className="font-mono bg-slate-950 px-1 rounded">{'${block_id.field.nested}'}</code>{' '}
          — вложенное поле
        </li>
        <li>
          <code className="font-mono bg-slate-950 px-1 rounded">{'${input.key}'}</code>{' '}
          — значение из input текущего блока
        </li>
      </ul>
      <p className="text-slate-400">
        Если путь не найден — пайплайн упадёт с PathNotFoundException, пустая строка не подставится.
      </p>
      <div className="bg-slate-950 border border-slate-800 rounded p-2 font-mono text-[10px] text-slate-300">
        Реализуй задачу: ${'{task_md.content}'}<br />
        Используй ветку ${'{create_branch.branch_name}'}
      </div>
    </div>
  )
}

/** Cheatsheet body for the `$.block.field OP value` expression form (conditions / inject_context). */
export function ExpressionHelpBody() {
  return (
    <div className="space-y-2">
      <p>
        Выражение для <code className="font-mono">condition</code>,{' '}
        <code className="font-mono">on_fail.inject_context</code> и{' '}
        <code className="font-mono">on_failure</code>. Если результат falsy — блок будет пропущен.
      </p>
      <ul className="space-y-1 list-disc pl-4">
        <li>
          <code className="font-mono bg-slate-950 px-1 rounded">$.block_id.field</code> — путь к полю
        </li>
        <li>
          Операторы:{' '}
          <code className="font-mono bg-slate-950 px-1 rounded">==</code>{' '}
          <code className="font-mono bg-slate-950 px-1 rounded">!=</code>{' '}
          <code className="font-mono bg-slate-950 px-1 rounded">{'>'}</code>{' '}
          <code className="font-mono bg-slate-950 px-1 rounded">{'<'}</code>{' '}
          <code className="font-mono bg-slate-950 px-1 rounded">{'>='}</code>{' '}
          <code className="font-mono bg-slate-950 px-1 rounded">{'<='}</code>{' '}
          <code className="font-mono bg-slate-950 px-1 rounded">in</code>
        </li>
        <li>Литералы: строки в кавычках <code className="font-mono">'low'</code>, числа, true/false</li>
      </ul>
      <div className="bg-slate-950 border border-slate-800 rounded p-2 font-mono text-[10px] text-slate-300 space-y-1">
        <div>$.analysis.estimated_complexity != 'low'</div>
        <div>$.run_tests.failed_count {'>'} 0</div>
        <div>$.code_generation.files_changed in ['src/Foo.java']</div>
      </div>
    </div>
  )
}

export default HelpPopover

import { ReactNode, useEffect, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import clsx from 'clsx'

interface Props {
  title: string
  /** Render content open by default. Default `false`. */
  defaultOpen?: boolean
  /**
   * External override that forces the section open regardless of user toggle —
   * used by the auto-expand-on-error mechanism in {@code SidePanel}. When this
   * goes from `false → true` we open; when it returns to `false` we leave the
   * section in whatever state the user last set.
   */
  forceOpen?: boolean
  /** Optional badge node rendered to the right of the title (e.g. red error dot). */
  badge?: ReactNode
  /** Stable test id (e.g. `section-essentials`). The button gets `${testId}-toggle`. */
  testId?: string
  children: ReactNode
}

/**
 * Generic accordion primitive used by the side panel for grouped block-config
 * sections (Essentials / Conditions & Retry / Advanced). Keyboard-accessible:
 * the title is rendered as a real `<button>` with `aria-expanded`.
 */
export function Section({
  title, defaultOpen = false, forceOpen = false, badge, testId, children,
}: Props) {
  const [open, setOpen] = useState(defaultOpen)

  // When forceOpen flips on (e.g. a new validation error appears in this
  // section), expand. We deliberately don't *close* when it flips back off —
  // user might be mid-edit and we shouldn't snap shut on them.
  useEffect(() => {
    if (forceOpen) setOpen(true)
  }, [forceOpen])

  const effectiveOpen = open || forceOpen

  return (
    <div
      data-testid={testId}
      data-open={effectiveOpen ? 'true' : 'false'}
      className="border-t border-slate-800 first:border-t-0"
    >
      <button
        type="button"
        data-testid={testId ? `${testId}-toggle` : undefined}
        onClick={() => setOpen(o => !o)}
        aria-expanded={effectiveOpen}
        className="w-full flex items-center gap-2 py-3 text-xs font-semibold text-slate-300 hover:text-slate-100 uppercase tracking-wide"
      >
        {effectiveOpen
          ? <ChevronDown className="w-3.5 h-3.5 flex-shrink-0 text-slate-500" />
          : <ChevronRight className="w-3.5 h-3.5 flex-shrink-0 text-slate-500" />}
        <span className="flex-1 text-left">{title}</span>
        {badge}
      </button>
      <div className={clsx('pb-4', effectiveOpen ? 'block' : 'hidden')}>
        {children}
      </div>
    </div>
  )
}

export default Section

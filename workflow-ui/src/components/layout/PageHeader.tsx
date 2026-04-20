import { Link } from 'react-router-dom'
import { ChevronRight } from 'lucide-react'

interface Breadcrumb {
  label: string
  href?: string
}

interface Props {
  title: string
  breadcrumbs?: Breadcrumb[]
  actions?: React.ReactNode
  description?: string
}

export default function PageHeader({ title, breadcrumbs, actions, description }: Props) {
  return (
    // On mobile the title stacks above the actions; on sm+ they sit side-by-side.
    <div className="flex flex-wrap items-start justify-between gap-y-3">
      <div className="min-w-0">
        {breadcrumbs && breadcrumbs.length > 0 && (
          <nav className="flex items-center gap-1 text-xs text-slate-500 mb-1.5 flex-wrap">
            {breadcrumbs.map((b, i) => (
              <span key={i} className="flex items-center gap-1">
                {i > 0 && <ChevronRight className="w-3 h-3 flex-shrink-0" />}
                {b.href ? (
                  <Link to={b.href} className="hover:text-slate-300 transition-colors truncate">{b.label}</Link>
                ) : (
                  <span className="text-slate-400 truncate">{b.label}</span>
                )}
              </span>
            ))}
          </nav>
        )}
        <h1 className="text-xl font-semibold text-white truncate">{title}</h1>
        {description && <p className="text-sm text-slate-500 mt-0.5">{description}</p>}
      </div>
      {actions && (
        <div className="flex items-center gap-2 flex-shrink-0">
          {actions}
        </div>
      )}
    </div>
  )
}

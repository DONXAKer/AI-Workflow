import { Link } from 'react-router-dom'
import clsx from 'clsx'

interface Props {
  label: string
  value: number
  color: 'blue' | 'amber' | 'green' | 'red' | 'slate'
  pulse?: boolean
  isLoading?: boolean
  href?: string
}

const COLOR_MAP = {
  blue:  { value: 'text-blue-300',  bg: 'bg-blue-900/30',  border: 'border-blue-800/50',  dot: 'bg-blue-400'  },
  amber: { value: 'text-amber-300', bg: 'bg-amber-900/30', border: 'border-amber-800/50', dot: 'bg-amber-400' },
  green: { value: 'text-green-300', bg: 'bg-green-900/30', border: 'border-green-800/50', dot: 'bg-green-400' },
  red:   { value: 'text-red-300',   bg: 'bg-red-900/30',   border: 'border-red-800/50',   dot: 'bg-red-400'   },
  slate: { value: 'text-slate-200', bg: 'bg-slate-800/50', border: 'border-slate-700',    dot: 'bg-slate-400' },
}

export default function StatCard({ label, value, color, pulse = false, isLoading = false, href }: Props) {
  const c = COLOR_MAP[color]
  const inner = (
    <>
      <div className="flex items-center justify-between mb-3">
        <p className="text-xs text-slate-500 uppercase tracking-wide font-medium">{label}</p>
        {/* Only show the pulse dot once real data is loaded and value is non-zero */}
        {!isLoading && pulse && value > 0 && (
          <span className="relative flex h-2 w-2">
            <span className={clsx('animate-ping absolute inline-flex h-full w-full rounded-full opacity-75', c.dot)} />
            <span className={clsx('relative inline-flex rounded-full h-2 w-2', c.dot)} />
          </span>
        )}
      </div>
      {isLoading ? (
        /* Shimmer bar replaces the number while data is loading */
        <div className="h-9 w-14 rounded-md bg-slate-700/60 animate-pulse" />
      ) : (
        <p className={clsx('text-3xl font-bold', c.value)}>{value}</p>
      )}
    </>
  )

  if (href) {
    return (
      <Link to={href} className={clsx('block rounded-xl border p-5 hover:opacity-90 transition-opacity', c.bg, c.border)}>
        {inner}
      </Link>
    )
  }

  return (
    <div className={clsx('rounded-xl border p-5', c.bg, c.border)}>
      {inner}
    </div>
  )
}

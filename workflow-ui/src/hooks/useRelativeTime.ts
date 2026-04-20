import { useState, useEffect } from 'react'

const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })

function getRelativeTime(dateStr: string): string {
  const now = Date.now()
  const then = new Date(dateStr).getTime()
  const diff = now - then

  if (diff < 60_000) return 'just now'
  if (diff < 3_600_000) return formatter.format(-Math.floor(diff / 60_000), 'minutes')
  if (diff < 86_400_000) return formatter.format(-Math.floor(diff / 3_600_000), 'hours')
  return formatter.format(-Math.floor(diff / 86_400_000), 'days')
}

export function useRelativeTime(dateStr: string | null | undefined): string {
  const [rel, setRel] = useState(() => (dateStr ? getRelativeTime(dateStr) : '—'))

  useEffect(() => {
    if (!dateStr) { setRel('—'); return }
    setRel(getRelativeTime(dateStr))
    const interval = setInterval(() => setRel(getRelativeTime(dateStr)), 30_000)
    return () => clearInterval(interval)
  }, [dateStr])

  return rel
}

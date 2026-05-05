import { useState, useEffect } from 'react'

function formatDuration(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000)
  const h = Math.floor(totalSeconds / 3600)
  const m = Math.floor((totalSeconds % 3600) / 60)
  const s = totalSeconds % 60
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

interface Props {
  startedAt: string
  completedAt?: string | null
  live?: boolean
}

export default function RunDuration({ startedAt, completedAt, live = false }: Props) {
  const [now, setNow] = useState(Date.now)

  useEffect(() => {
    if (!live || completedAt) return
    const interval = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(interval)
  }, [live, completedAt])

  const start = new Date(startedAt).getTime()
  // Guard: if startedAt is missing/unparseable Date returns NaN — show a dash
  // instead of letting NaN propagate through formatDuration into "NaNs".
  if (!startedAt || isNaN(start)) return <span>—</span>
  const end = completedAt ? new Date(completedAt).getTime() : now
  // Clock skew between server and browser can put `end < start` for a brief moment
  // (server timestamp slightly ahead of browser clock). Clamping to 0 prevents
  // the duration from disappearing as a dash on freshly-started runs.
  const duration = Math.max(0, isNaN(end) ? 0 : end - start)

  return <span>{formatDuration(duration)}</span>
}

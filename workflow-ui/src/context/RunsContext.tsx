import { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react'
import { api } from '../services/api'
import { connectToGlobalRuns } from '../services/websocket'

interface RunsContextValue {
  activeCount: number
  pendingApprovalCount: number
  refresh: () => void
}

const RunsContext = createContext<RunsContextValue>({
  activeCount: 0,
  pendingApprovalCount: 0,
  refresh: () => {},
})

export function RunsProvider({ children }: { children: React.ReactNode }) {
  const [activeCount, setActiveCount] = useState(0)
  const [pendingApprovalCount, setPendingApprovalCount] = useState(0)
  const refreshRef = useRef<() => void>(null!)
  // Debounce timer — prevents rapid-fire stat fetches when multiple WS events
  // arrive in quick succession (e.g. BLOCK_STARTED followed by BLOCK_COMPLETE
  // within the same pipeline step).
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const refresh = useCallback(() => {
    api.getRunStats()
      .then(stats => {
        setActiveCount(stats.activeRuns)
        setPendingApprovalCount(stats.awaitingApproval)
      })
      .catch(() => {})
  }, [])

  refreshRef.current = refresh

  useEffect(() => {
    refresh()
    const disconnect = connectToGlobalRuns(() => {
      // Debounce: cancel any pending timer and schedule a fresh refresh.
      // This collapses bursts of WS events (BLOCK_STARTED, BLOCK_COMPLETE, …)
      // into a single stat fetch 500 ms after the last message in the burst.
      if (debounceTimerRef.current !== null) {
        clearTimeout(debounceTimerRef.current)
      }
      debounceTimerRef.current = setTimeout(() => {
        debounceTimerRef.current = null
        refreshRef.current?.()
      }, 500)
    })
    return () => {
      disconnect()
      // Clean up any pending debounce timer on unmount
      if (debounceTimerRef.current !== null) {
        clearTimeout(debounceTimerRef.current)
      }
    }
  }, [refresh])

  return (
    <RunsContext.Provider value={{ activeCount, pendingApprovalCount, refresh }}>
      {children}
    </RunsContext.Provider>
  )
}

export function useRunsContext() {
  return useContext(RunsContext)
}

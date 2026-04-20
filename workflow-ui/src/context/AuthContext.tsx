import { createContext, useContext, useEffect, useState, useCallback, ReactNode } from 'react'
import { api } from '../services/api'
import { CurrentUser } from '../types'

interface AuthState {
  user: CurrentUser | null
  loading: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      const me = await api.me()
      setUser(me)
    } catch {
      setUser(null)
    }
  }, [])

  useEffect(() => {
    // Priming CSRF: GET hits a safe endpoint so Spring issues the XSRF-TOKEN cookie
    // BEFORE the first POST (login). Without this, the initial login lacks the token.
    fetch('/api/auth/me', { credentials: 'same-origin' })
      .catch(() => {})
      .finally(() => refresh().finally(() => setLoading(false)))
  }, [refresh])

  const login = useCallback(async (username: string, password: string) => {
    const me = await api.login(username, password)
    setUser(me)
  }, [])

  const logout = useCallback(async () => {
    try { await api.logout() } catch { /* ignore — server may already have killed session */ }
    setUser(null)
  }, [])

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

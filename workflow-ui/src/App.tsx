import { useState, useEffect, useRef } from 'react'
import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { Menu, X, Loader2 } from 'lucide-react'
import { RunsProvider } from './context/RunsContext'
import { AuthProvider, useAuth } from './context/AuthContext'
import { ToastProvider } from './context/ToastContext'
import ToastContainer from './components/ToastContainer'
import Sidebar from './components/layout/Sidebar'
import DashboardPage from './pages/DashboardPage'
import PipelinesPage from './pages/PipelinesPage'
import ActiveRunsPage from './pages/ActiveRunsPage'
import RunHistoryPage from './pages/RunHistoryPage'
import RunPage from './pages/RunPage'
import LoginPage from './pages/LoginPage'
import IntegrationsSettings from './components/IntegrationsSettings'
import AgentProfilesSettings from './components/AgentProfilesSettings'
import AuditLogPage from './pages/AuditLogPage'
import KillSwitchPage from './pages/KillSwitchPage'
import CostDashboardPage from './pages/CostDashboardPage'
import ProjectsSettingsPage from './pages/ProjectsSettingsPage'
import UsersSettingsPage from './pages/UsersSettingsPage'

/**
 * Thin top bar shown on small screens. Contains a hamburger button that opens
 * the sidebar as a slide-in drawer overlay.
 */
function MobileTopBar({ onOpen }: { onOpen: () => void }) {
  return (
    <header className="lg:hidden flex items-center h-12 px-4 bg-slate-900 border-b border-slate-800 sticky top-0 z-30">
      <button
        type="button"
        onClick={onOpen}
        aria-label="Open navigation"
        className="p-1.5 rounded-md text-slate-400 hover:text-white hover:bg-slate-800 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
      >
        <Menu className="w-5 h-5" />
      </button>
      <span className="ml-3 text-sm font-semibold text-white tracking-tight">Workflow</span>
    </header>
  )
}

/**
 * Mobile drawer that wraps the Sidebar with a backdrop overlay.
 * Closes on outside click, Escape key, or navigation.
 */
function MobileDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const drawerRef = useRef<HTMLDivElement>(null)

  // Close on Escape
  useEffect(() => {
    if (!open) return
    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKey)
    return () => document.removeEventListener('keydown', handleKey)
  }, [open, onClose])

  // Prevent body scroll while drawer is open
  useEffect(() => {
    if (open) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => { document.body.style.overflow = '' }
  }, [open])

  if (!open) return null

  return (
    <div className="lg:hidden fixed inset-0 z-40 flex">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-slate-950/70 backdrop-blur-sm"
        aria-hidden="true"
        onClick={onClose}
      />
      {/* Drawer panel */}
      <div
        ref={drawerRef}
        role="dialog"
        aria-label="Navigation"
        aria-modal="true"
        className="relative z-50 flex-shrink-0"
      >
        {/* Close button inside drawer */}
        <button
          type="button"
          onClick={onClose}
          aria-label="Close navigation"
          className="absolute top-3 right-3 p-1 rounded-md text-slate-400 hover:text-white hover:bg-slate-800 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 z-10"
        >
          <X className="w-4 h-4" />
        </button>
        {/*
          Render the same Sidebar component.
          We override the `hidden lg:flex` that the sidebar now carries on
          desktop by wrapping it in a plain flex container that always shows.
        */}
        <div className="flex">
          <Sidebar />
        </div>
      </div>
    </div>
  )
}

function AppLayout() {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const location = useLocation()

  // Close drawer on every navigation
  useEffect(() => {
    setDrawerOpen(false)
  }, [location.pathname, location.search])

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col lg:flex-row">
      {/* Desktop sidebar — hidden below lg breakpoint */}
      <div className="hidden lg:flex">
        <Sidebar />
      </div>

      {/* Mobile top bar */}
      <MobileTopBar onOpen={() => setDrawerOpen(true)} />

      {/* Mobile drawer */}
      <MobileDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)} />

      {/*
        Page content. The outer <main> is intentionally stable (no key) so
        it never remounts between navigations — this is important for any
        page component that owns long-lived resources (e.g. WebSocket
        connections in RunPage). The keyed inner div re-triggers the
        fade-in CSS animation on each navigation without tearing down the
        DOM node that React renders routes into.
      */}
      <main className="flex-1 overflow-auto min-h-screen">
        <div key={location.key} className="animate-fade-in">
          <Routes>
            <Route path="/" element={<DashboardPage />} />
            <Route path="/pipelines" element={<PipelinesPage />} />
            <Route path="/runs/active" element={<ActiveRunsPage />} />
            <Route path="/runs/history" element={<RunHistoryPage />} />
            <Route path="/runs/:runId" element={<RunPage />} />
            {/* Legacy single-run path — kept for backward compatibility with external links */}
            <Route path="/run/:runId" element={<RunPage />} />
            <Route path="/settings/integrations" element={<IntegrationsSettings />} />
            <Route path="/settings/agent-profiles" element={<AgentProfilesSettings />} />
            <Route path="/settings/audit" element={<AuditLogPage />} />
            <Route path="/settings/kill-switch" element={<KillSwitchPage />} />
            <Route path="/cost" element={<CostDashboardPage />} />
            <Route path="/settings/projects" element={<ProjectsSettingsPage />} />
            <Route path="/settings/users" element={<UsersSettingsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  )
}

function RequireAuth() {
  const { user, loading } = useAuth()
  const location = useLocation()
  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-950 text-slate-400">
        <Loader2 className="w-5 h-5 animate-spin" />
      </div>
    )
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return (
    <RunsProvider>
      <AppLayout />
    </RunsProvider>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/*" element={<RequireAuth />} />
        </Routes>
        <ToastContainer />
      </ToastProvider>
    </AuthProvider>
  )
}

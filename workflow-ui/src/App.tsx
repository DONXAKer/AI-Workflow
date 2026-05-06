import { useState, useEffect, useRef } from 'react'
import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { Menu, X, Loader2 } from 'lucide-react'
import { RunsProvider } from './context/RunsContext'
import { AuthProvider, useAuth } from './context/AuthContext'
import { ToastProvider } from './context/ToastContext'
import ToastContainer from './components/ToastContainer'
import Sidebar from './components/layout/Sidebar'

import ProjectsListPage from './pages/ProjectsListPage'
import ProjectWorkspacePage from './pages/ProjectWorkspacePage'
import SmartStartTab from './pages/project/SmartStartTab'
import LaunchTab from './pages/project/LaunchTab'
import ActiveTab from './pages/project/ActiveTab'
import HistoryTab from './pages/project/HistoryTab'
import IntegrationsTab from './pages/project/IntegrationsTab'
import McpServersTab from './pages/project/McpServersTab'
import SettingsTab from './pages/project/SettingsTab'

import RunPage from './pages/RunPage'
import RunHistoryPage from './pages/RunHistoryPage'
import LoginPage from './pages/LoginPage'
import ActiveRunsPage from './pages/ActiveRunsPage'

import IntegrationsSettings from './components/IntegrationsSettings'
import AgentProfilesSettings from './components/AgentProfilesSettings'
import AuditLogPage from './pages/AuditLogPage'
import KillSwitchPage from './pages/KillSwitchPage'
import CostDashboardPage from './pages/CostDashboardPage'
import UsersSettingsPage from './pages/UsersSettingsPage'
import ProjectsSettingsPage from './pages/ProjectsSettingsPage'
import SystemLayout from './pages/SystemLayout'

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

function MobileDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const drawerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKey)
    return () => document.removeEventListener('keydown', handleKey)
  }, [open, onClose])

  useEffect(() => {
    document.body.style.overflow = open ? 'hidden' : ''
    return () => { document.body.style.overflow = '' }
  }, [open])

  if (!open) return null

  return (
    <div className="lg:hidden fixed inset-0 z-40 flex">
      <div className="absolute inset-0 bg-slate-950/70 backdrop-blur-sm" aria-hidden="true" onClick={onClose} />
      <div ref={drawerRef} role="dialog" aria-label="Navigation" aria-modal="true" className="relative z-50 flex-shrink-0">
        <button
          type="button"
          onClick={onClose}
          aria-label="Close navigation"
          className="absolute top-3 right-3 p-1 rounded-md text-slate-400 hover:text-white hover:bg-slate-800 transition-colors z-10"
        >
          <X className="w-4 h-4" />
        </button>
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

  useEffect(() => { setDrawerOpen(false) }, [location.pathname, location.search])

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col lg:flex-row">
      <div className="hidden lg:flex">
        <Sidebar />
      </div>

      <MobileTopBar onOpen={() => setDrawerOpen(true)} />
      <MobileDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)} />

      <main className="flex-1 overflow-auto min-h-screen">
        <div key={location.key} className="animate-fade-in">
          <Routes>
            {/* Main: projects list */}
            <Route path="/" element={<ProjectsListPage />} />

            {/* Project workspace */}
            <Route path="/projects/:slug" element={<ProjectWorkspacePage />}>
              <Route index element={<Navigate to="smart-start" replace />} />
              <Route path="smart-start" element={<SmartStartTab />} />
              <Route path="launch" element={<LaunchTab />} />
              <Route path="active" element={<ActiveTab />} />
              <Route path="history" element={<HistoryTab />} />
              <Route path="integrations" element={<IntegrationsTab />} />
              <Route path="mcp" element={<McpServersTab />} />
              <Route path="settings" element={<SettingsTab />} />
              {/* Run opened from inside a project — keeps project tab bar visible */}
              <Route path="runs/:runId" element={<RunPage />} />
            </Route>

            {/* Run detail — global: redirects to project-scoped URL if run has a project */}
            <Route path="/runs/:runId" element={<RunPage />} />
            <Route path="/run/:runId" element={<RunPage />} />

            {/* System settings (admin) */}
            <Route path="/system" element={<SystemLayout />}>
              <Route index element={<Navigate to="users" replace />} />
              <Route path="users" element={<UsersSettingsPage />} />
              <Route path="integrations" element={<IntegrationsSettings />} />
              <Route path="agent-profiles" element={<AgentProfilesSettings />} />
              <Route path="audit" element={<AuditLogPage />} />
              <Route path="kill-switch" element={<KillSwitchPage />} />
              <Route path="cost" element={<CostDashboardPage />} />
              <Route path="projects" element={<ProjectsSettingsPage />} />
            </Route>

            {/* Legacy pages */}
            <Route path="/runs/active" element={<ActiveRunsPage allProjects />} />
            <Route path="/pipelines" element={<Navigate to="/" replace />} />

            {/* Legacy redirects */}
            <Route path="/settings/*" element={<Navigate to="/system/users" replace />} />
            <Route path="/runs/history" element={<RunHistoryPage />} />
            <Route path="/cost" element={<Navigate to="/system/cost" replace />} />

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

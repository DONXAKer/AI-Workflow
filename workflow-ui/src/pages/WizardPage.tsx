import { useState, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { ChevronRight, ChevronLeft, Loader2, AlertCircle, Check, Search, FolderOpen, Zap, Settings, History, TriangleAlert } from 'lucide-react'
import { api } from '../services/api'
import { setCurrentProjectSlug } from '../services/projectContext'
import { ProjectInfo, IntegrationConfig, EntryPoint } from '../types'
import clsx from 'clsx'

type PipelineInfo = { path: string; name: string; pipelineName?: string; description?: string; error?: string }
type TaskIssue = { id: string; readableId: string; summary: string; description: string; status: string; url: string }

// ──────────────────────────────────────────────────────────────────────────────
// Wizard preferences — persisted per project in localStorage
// ──────────────────────────────────────────────────────────────────────────────
type WizardPrefs = {
  pipelinePath: string
  pipelineName: string
  entryPointId: string
  entryPointName: string
  provider: string
  usedAt: string
}
const PREFS_KEY = 'wizard_prefs_v1'

function loadPrefs(): Record<string, WizardPrefs> {
  try { return JSON.parse(localStorage.getItem(PREFS_KEY) ?? '{}') } catch { return {} }
}

function savePrefs(projectSlug: string, prefs: WizardPrefs) {
  const all = loadPrefs()
  all[projectSlug] = prefs
  localStorage.setItem(PREFS_KEY, JSON.stringify(all))
}

function getPrefs(projectSlug: string): WizardPrefs | null {
  return loadPrefs()[projectSlug] ?? null
}

// ──────────────────────────────────────────────────────────────────────────────
// Step indicator
// ──────────────────────────────────────────────────────────────────────────────
function StepIndicator({ step }: { step: number }) {
  const steps = ['Задача', 'Проект', 'Пайплайн']
  return (
    <div className="flex items-center gap-2">
      {steps.map((label, i) => {
        const idx = i + 1
        const done = idx < step
        const active = idx === step
        return (
          <div key={idx} className="flex items-center gap-2">
            <div className={clsx(
              'w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold shrink-0 transition-colors',
              done && 'bg-emerald-600 text-white',
              active && 'bg-blue-600 text-white ring-2 ring-blue-400/40',
              !done && !active && 'bg-slate-800 text-slate-500',
            )}>
              {done ? <Check className="w-3.5 h-3.5" /> : idx}
            </div>
            <span className={clsx(
              'text-sm font-medium transition-colors',
              active ? 'text-white' : done ? 'text-slate-400' : 'text-slate-600',
            )}>{label}</span>
            {i < steps.length - 1 && (
              <ChevronRight className="w-4 h-4 text-slate-700 shrink-0" />
            )}
          </div>
        )
      })}
    </div>
  )
}

// ──────────────────────────────────────────────────────────────────────────────
// Step 1: Task
// ──────────────────────────────────────────────────────────────────────────────
function TaskStep({
  provider, setProvider,
  issueId, setIssueId,
  issue, setIssue,
}: {
  provider: string
  setProvider: (v: string) => void
  issueId: string
  setIssueId: (v: string) => void
  issue: TaskIssue | null
  setIssue: (v: TaskIssue | null) => void
}) {
  const [integrations, setIntegrations] = useState<IntegrationConfig[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.listIntegrations()
      .then(data => setIntegrations(data))
      .catch(() => {})
  }, [])

  const trackerIntegrations = integrations.filter(i =>
    ['YOUTRACK', 'JIRA', 'GITHUB', 'GITLAB', 'LINEAR'].includes((i.type as string).toUpperCase())
  )

  const load = async () => {
    if (!issueId.trim()) return
    setLoading(true)
    setError(null)
    setIssue(null)
    try {
      const result = await api.previewTrackerIssue({ issueId: issueId.trim(), provider })
      if ('error' in result && result.error) {
        setError(result.error as string)
      } else {
        setIssue(result as TaskIssue)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить задачу')
    } finally {
      setLoading(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') load()
  }

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-[1fr_2fr] gap-3">
        {/* Provider select */}
        <div>
          <div className="flex items-center justify-between mb-1.5">
            <label className="text-xs font-medium text-slate-400 uppercase tracking-wide">Трекер</label>
            <Link
              to="/system/integrations"
              title="Настроить интеграции"
              className="flex items-center gap-1 text-[11px] text-slate-500 hover:text-blue-400 transition-colors"
            >
              <Settings className="w-3 h-3" />
              Настроить
            </Link>
          </div>
          {trackerIntegrations.length > 0 ? (
            <select
              value={provider}
              onChange={e => { setProvider(e.target.value); setIssue(null) }}
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              {trackerIntegrations.map(i => (
                <option key={i.id} value={(i.type as string).toLowerCase()}>
                  {i.displayName || i.name}
                </option>
              ))}
            </select>
          ) : (
            <div className="space-y-2">
              <select
                value={provider}
                onChange={e => { setProvider(e.target.value); setIssue(null) }}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="youtrack">YouTrack</option>
                <option value="jira">Jira</option>
                <option value="github">GitHub Issues</option>
                <option value="gitlab">GitLab Issues</option>
              </select>
              <p className="text-[11px] text-amber-500/80">
                Интеграция не настроена —{' '}
                <Link to="/system/integrations" className="underline hover:text-amber-400">добавьте её в настройках</Link>
              </p>
            </div>
          )}
        </div>

        {/* Issue ID input */}
        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">ID задачи</label>
          <div className="flex gap-2">
            <input
              type="text"
              value={issueId}
              onChange={e => { setIssueId(e.target.value); setIssue(null) }}
              onKeyDown={handleKeyDown}
              placeholder="PROJ-42"
              className="flex-1 bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <button
              type="button"
              onClick={load}
              disabled={!issueId.trim() || loading}
              className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-slate-700 hover:bg-slate-600 disabled:opacity-50 disabled:cursor-not-allowed text-sm text-white transition-colors"
            >
              {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Search className="w-4 h-4" />}
              Найти
            </button>
          </div>
        </div>
      </div>

      {error && (
        <div className="flex items-start gap-2 text-red-400 text-sm bg-red-900/20 border border-red-800/40 rounded-lg px-3 py-2.5">
          <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
          {error}
        </div>
      )}

      {issue && (
        <div className="bg-slate-800/60 border border-emerald-700/40 rounded-xl p-4 space-y-2">
          <div className="flex items-start justify-between gap-3">
            <div className="space-y-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-xs font-mono text-slate-500">{issue.readableId}</span>
                <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-medium bg-slate-700 text-slate-300 border border-slate-600">
                  {issue.status}
                </span>
              </div>
              <p className="text-sm font-semibold text-white leading-snug">{issue.summary}</p>
            </div>
            <Check className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
          </div>
          {issue.description && (
            <p className="text-xs text-slate-400 line-clamp-3 leading-relaxed">
              {issue.description.slice(0, 400)}{issue.description.length > 400 ? '…' : ''}
            </p>
          )}
          {issue.url && (
            <a
              href={issue.url}
              target="_blank"
              rel="noopener noreferrer"
              className="text-xs text-blue-400 hover:text-blue-300 transition-colors"
            >
              Открыть в трекере →
            </a>
          )}
        </div>
      )}
    </div>
  )
}

// ──────────────────────────────────────────────────────────────────────────────
// Step 2: Project
// ──────────────────────────────────────────────────────────────────────────────
function ProjectStep({
  selected, onSelect,
}: {
  selected: ProjectInfo | null
  onSelect: (p: ProjectInfo) => void
}) {
  const [projects, setProjects] = useState<ProjectInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const prefs = loadPrefs()

  useEffect(() => {
    api.listProjects()
      .then(setProjects)
      .catch(() => setError('Не удалось загрузить проекты'))
      .finally(() => setLoading(false))
  }, [])

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12 text-slate-500">
        <Loader2 className="w-5 h-5 animate-spin" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center gap-2 text-red-400 text-sm">
        <AlertCircle className="w-4 h-4" /> {error}
      </div>
    )
  }

  // Sort: projects with saved prefs first
  const sorted = [...projects].sort((a, b) => {
    const aHas = !!prefs[a.slug]
    const bHas = !!prefs[b.slug]
    if (aHas && !bHas) return -1
    if (!aHas && bHas) return 1
    return 0
  })

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
      {sorted.map(p => {
        const pref = prefs[p.slug]
        const isSelected = selected?.slug === p.slug
        return (
          <button
            key={p.slug}
            type="button"
            onClick={() => onSelect(p)}
            className={clsx(
              'text-left border rounded-xl p-4 transition-all',
              isSelected
                ? 'border-blue-500 bg-blue-900/20 ring-1 ring-blue-500'
                : 'border-slate-700 bg-slate-800/60 hover:border-slate-500',
            )}
          >
            <div className="flex items-start gap-3">
              <FolderOpen className={clsx('w-4 h-4 mt-0.5 shrink-0', isSelected ? 'text-blue-400' : 'text-slate-500')} />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-semibold text-white truncate">{p.displayName || p.slug}</p>
                {p.workingDir
                  ? <p className="text-xs text-slate-500 truncate mt-0.5 font-mono">{p.workingDir}</p>
                  : <p className="flex items-center gap-1 text-[11px] text-amber-500/80 mt-0.5">
                      <TriangleAlert className="w-3 h-3 shrink-0" />
                      Рабочая папка не настроена
                    </p>
                }
                {pref && (
                  <p className="flex items-center gap-1 text-[11px] text-emerald-500/80 mt-1.5 truncate">
                    <History className="w-3 h-3 shrink-0" />
                    {pref.pipelineName} · {pref.entryPointName}
                  </p>
                )}
              </div>
              {isSelected && (
                <Check className="w-4 h-4 text-blue-400 shrink-0 ml-auto mt-0.5" />
              )}
            </div>
          </button>
        )
      })}
    </div>
  )
}

// ──────────────────────────────────────────────────────────────────────────────
// Step 3: Pipeline
// ──────────────────────────────────────────────────────────────────────────────
function PipelineStep({
  projectSlug,
  selectedPipeline, setSelectedPipeline,
  selectedEntryPoint, setSelectedEntryPoint,
}: {
  projectSlug: string
  selectedPipeline: PipelineInfo | null
  setSelectedPipeline: (p: PipelineInfo | null) => void
  selectedEntryPoint: EntryPoint | null
  setSelectedEntryPoint: (ep: EntryPoint | null) => void
}) {
  const [pipelines, setPipelines] = useState<PipelineInfo[]>([])
  const [entryPoints, setEntryPoints] = useState<EntryPoint[]>([])
  const [loadingPipelines, setLoadingPipelines] = useState(true)
  const [loadingEps, setLoadingEps] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [prefApplied, setPrefApplied] = useState(false)
  const pref = getPrefs(projectSlug)

  useEffect(() => {
    setLoadingPipelines(true)
    setPrefApplied(false)
    api.listPipelines()
      .then(data => {
        const valid = data.filter(p => !p.error)
        setPipelines(valid)
        // Auto-select from saved pref or fall back to first
        const preferred = pref ? valid.find(p => p.path === pref.pipelinePath) : null
        const initial = preferred ?? (valid.length > 0 ? valid[0] : null)
        setSelectedPipeline(initial)
        if (preferred) setPrefApplied(true)
      })
      .catch(() => setError('Не удалось загрузить пайплайны'))
      .finally(() => setLoadingPipelines(false))
  }, [projectSlug]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!selectedPipeline) { setEntryPoints([]); setSelectedEntryPoint(null); return }
    setLoadingEps(true)
    api.getEntryPoints(selectedPipeline.path)
      .then(eps => {
        setEntryPoints(eps)
        // Priority: saved pref entry point → from_task → first
        const savedEp = pref && selectedPipeline.path === pref.pipelinePath
          ? eps.find(ep => ep.id === pref.entryPointId)
          : null
        const fromTask = eps.find(ep => ep.id === 'from_task')
        setSelectedEntryPoint(savedEp ?? fromTask ?? (eps.length > 0 ? eps[0] : null))
      })
      .catch(() => { setEntryPoints([]); setSelectedEntryPoint(null) })
      .finally(() => setLoadingEps(false))
  }, [selectedPipeline]) // eslint-disable-line react-hooks/exhaustive-deps

  if (loadingPipelines) {
    return (
      <div className="flex items-center justify-center py-12 text-slate-500">
        <Loader2 className="w-5 h-5 animate-spin" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center gap-2 text-red-400 text-sm">
        <AlertCircle className="w-4 h-4" /> {error}
      </div>
    )
  }

  const autoEntryPoint = selectedEntryPoint?.id === 'from_task'
  const showEps = entryPoints.length > 0 && !autoEntryPoint

  return (
    <div className="space-y-5">
      {/* Saved pref banner */}
      {prefApplied && pref && (
        <div className="flex items-center gap-2 text-xs text-emerald-400 bg-emerald-900/20 border border-emerald-800/40 rounded-lg px-3 py-2">
          <History className="w-3.5 h-3.5 shrink-0" />
          Предзаполнено из последнего запуска: <span className="font-medium">{pref.pipelineName} · {pref.entryPointName}</span>
        </div>
      )}

      {/* Pipeline pills */}
      <div>
        <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">Пайплайн</label>
        <div className="flex flex-wrap gap-2">
          {pipelines.map(p => (
            <button
              key={p.path}
              type="button"
              onClick={() => setSelectedPipeline(p)}
              className={clsx(
                'px-3 py-1.5 rounded-lg text-sm font-medium border transition-all',
                selectedPipeline?.path === p.path
                  ? 'border-blue-500 bg-blue-900/30 text-blue-300 ring-1 ring-blue-500'
                  : 'border-slate-700 bg-slate-800 text-slate-300 hover:border-slate-500',
              )}
            >
              {p.pipelineName || p.name}
            </button>
          ))}
        </div>
        {selectedPipeline?.description && (
          <p className="text-xs text-slate-500 mt-2">{selectedPipeline.description}</p>
        )}
      </div>

      {/* Entry point */}
      {loadingEps && (
        <div className="flex items-center gap-2 text-slate-500 text-sm">
          <Loader2 className="w-4 h-4 animate-spin" /> Загружаю точки входа…
        </div>
      )}

      {!loadingEps && autoEntryPoint && (
        <div className="flex items-center gap-2 text-emerald-400 text-sm">
          <Check className="w-4 h-4" />
          Точка входа <span className="font-mono text-emerald-300">from_task</span> выбрана автоматически
        </div>
      )}

      {!loadingEps && showEps && (
        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">Точка входа</label>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {entryPoints.map(ep => (
              <button
                key={ep.id}
                type="button"
                onClick={() => setSelectedEntryPoint(ep)}
                className={clsx(
                  'text-left border rounded-lg p-3 text-sm transition-all',
                  selectedEntryPoint?.id === ep.id
                    ? 'border-blue-500 bg-blue-900/20 text-blue-200'
                    : 'border-slate-700 bg-slate-800 text-slate-300 hover:border-slate-500',
                )}
              >
                <p className="font-medium">{ep.name}</p>
                {ep.description && (
                  <p className="text-xs text-slate-500 mt-0.5">{ep.description}</p>
                )}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ──────────────────────────────────────────────────────────────────────────────
// Main wizard
// ──────────────────────────────────────────────────────────────────────────────
export default function WizardPage() {
  const navigate = useNavigate()

  const [step, setStep] = useState(1)

  // Step 1 state
  const [provider, setProvider] = useState('youtrack')
  const [issueId, setIssueId] = useState('')
  const [issue, setIssue] = useState<TaskIssue | null>(null)

  // Step 2 state
  const [selectedProject, setSelectedProject] = useState<ProjectInfo | null>(null)

  // Step 3 state
  const [selectedPipeline, setSelectedPipeline] = useState<PipelineInfo | null>(null)
  const [selectedEntryPoint, setSelectedEntryPoint] = useState<EntryPoint | null>(null)

  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [preflightErrors, setPreflightErrors] = useState<{ code: string; message: string }[]>([])

  const canNext1 = issue !== null
  const canNext2 = selectedProject !== null
  const canStart = selectedPipeline !== null && selectedEntryPoint !== null

  const start = async () => {
    if (!selectedPipeline || !selectedEntryPoint || !selectedProject || !issue) return
    setSubmitting(true)
    setSubmitError(null)
    setPreflightErrors([])
    try {
      // Set project context so X-Project-Slug header matches the selected project
      setCurrentProjectSlug(selectedProject.slug)
      const run = await api.startRun({
        configPath: selectedPipeline.path,
        entryPointId: selectedEntryPoint.id,
        inputs: {
          issue_id: issue.readableId,
          tracker_provider: provider,
        },
      })
      savePrefs(selectedProject.slug, {
        pipelinePath: selectedPipeline.path,
        pipelineName: selectedPipeline.pipelineName || selectedPipeline.name,
        entryPointId: selectedEntryPoint.id,
        entryPointName: selectedEntryPoint.name,
        provider,
        usedAt: new Date().toISOString(),
      })
      const runId = run.id ?? run.runId
      navigate(`/projects/${selectedProject.slug}/runs/${runId}`)
    } catch (e: unknown) {
      // Try to parse preflight 422 response
      const body = (e as { body?: unknown })?.body
      if (body && typeof body === 'object') {
        const b = body as Record<string, unknown>
        if (b.error === 'Environment not ready' && Array.isArray(b.errors)) {
          setPreflightErrors(b.errors as { code: string; message: string }[])
          setSubmitError('Окружение не готово к запуску')
        } else {
          setSubmitError(String(b.error ?? b.message ?? 'Не удалось запустить пайплайн'))
        }
      } else {
        setSubmitError(e instanceof Error ? e.message : 'Не удалось запустить пайплайн')
      }
      setSubmitting(false)
    }
  }

  return (
    <div className="max-w-2xl mx-auto px-4 sm:px-6 py-10 space-y-8">
      {/* Header */}
      <div>
        <div className="flex items-center gap-2 mb-1">
          <Zap className="w-5 h-5 text-blue-400" />
          <h1 className="text-xl font-semibold text-white">Быстрый старт</h1>
        </div>
        <p className="text-sm text-slate-500">Выберите задачу, проект и пайплайн — система сделает остальное</p>
      </div>

      {/* Step indicator */}
      <StepIndicator step={step} />

      {/* Step content */}
      <div className="bg-slate-900/60 border border-slate-800 rounded-2xl p-6 min-h-[240px]">
        {step === 1 && (
          <TaskStep
            provider={provider} setProvider={setProvider}
            issueId={issueId} setIssueId={setIssueId}
            issue={issue} setIssue={setIssue}
          />
        )}
        {step === 2 && (
          <ProjectStep
            selected={selectedProject}
            onSelect={p => { setSelectedProject(p); setSelectedPipeline(null); setSelectedEntryPoint(null) }}
          />
        )}
        {step === 3 && (
          <PipelineStep
            projectSlug={selectedProject?.slug ?? ''}
            selectedPipeline={selectedPipeline} setSelectedPipeline={setSelectedPipeline}
            selectedEntryPoint={selectedEntryPoint} setSelectedEntryPoint={setSelectedEntryPoint}
          />
        )}
      </div>

      {/* Navigation */}
      <div className="flex items-center justify-between">
        <button
          type="button"
          onClick={() => setStep(s => s - 1)}
          disabled={step === 1}
          className="flex items-center gap-1.5 px-4 py-2 rounded-lg border border-slate-700 text-sm text-slate-300 hover:border-slate-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          <ChevronLeft className="w-4 h-4" /> Назад
        </button>

        <div className="flex items-center gap-3">
          {submitError && (
            <div className="text-xs text-red-400 max-w-xs">
              <p>{submitError}</p>
              {preflightErrors.map((e, i) => (
                <p key={i} className="text-amber-400 mt-0.5">· {e.message}</p>
              ))}
            </div>
          )}
          {step < 3 ? (
            <button
              type="button"
              onClick={() => setStep(s => s + 1)}
              disabled={(step === 1 && !canNext1) || (step === 2 && !canNext2)}
              className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-500 disabled:opacity-40 disabled:cursor-not-allowed text-sm text-white font-medium transition-colors"
            >
              Далее <ChevronRight className="w-4 h-4" />
            </button>
          ) : (
            <button
              type="button"
              onClick={start}
              disabled={!canStart || submitting}
              className="flex items-center gap-2 px-5 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 disabled:opacity-40 disabled:cursor-not-allowed text-sm text-white font-semibold transition-colors"
            >
              {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Zap className="w-4 h-4" />}
              Запустить
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

import { useState, useEffect } from 'react'
import { useNavigate, useSearchParams, useLocation } from 'react-router-dom'
import { Play, AlertCircle, Loader2, ChevronRight } from 'lucide-react'
import { api } from '../services/api'
import { EntryPoint } from '../types'
import clsx from 'clsx'
import PageHeader from '../components/layout/PageHeader'

type PipelineInfo = { path: string; name: string; pipelineName?: string; description?: string; error?: string }

export default function PipelinesPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const location = useLocation()
  const locationState = location.state as { pipeline?: string; requirement?: string } | null
  const [pipelines, setPipelines] = useState<PipelineInfo[]>([])
  const [loadingPipelines, setLoadingPipelines] = useState(true)
  const [pipelinesError, setPipelinesError] = useState<string | null>(null)

  const [selectedPipeline, setSelectedPipeline] = useState('')
  const [entryPoints, setEntryPoints] = useState<EntryPoint[]>([])
  const [loadingEntryPoints, setLoadingEntryPoints] = useState(false)
  const [selectedEntryPoint, setSelectedEntryPoint] = useState<EntryPoint | null>(null)
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [dryRun, setDryRun] = useState(false)

  // Load pipelines on mount
  useEffect(() => {
    const prefillRequirement = searchParams.get('requirement') ?? locationState?.requirement ?? ''
    const prefillPipeline = searchParams.get('pipeline') ?? locationState?.pipeline ?? ''

    setLoadingPipelines(true)
    api.listPipelines()
      .then(data => {
        setPipelines(data)

        if (prefillPipeline) {
          const byPath = data.find(p => p.path === prefillPipeline)
          const byName = data.find(p => p.pipelineName === prefillPipeline || p.name === prefillPipeline)
          const match = byPath ?? byName
          setSelectedPipeline(match ? match.path : data[0]?.path ?? '')
        } else if (data.length > 0) {
          setSelectedPipeline(data[0].path)
        }

        // Prefill requirement into fieldValues so it's available when entry point loads
        if (prefillRequirement) {
          setFieldValues(prev => ({ ...prev, requirement: prefillRequirement }))
        }
      })
      .catch(() => setPipelinesError('Не удалось загрузить пайплайны'))
      .finally(() => setLoadingPipelines(false))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Fetch entry points when pipeline changes
  useEffect(() => {
    if (!selectedPipeline) {
      setEntryPoints([])
      setSelectedEntryPoint(null)
      return
    }

    setLoadingEntryPoints(true)
    setSelectedEntryPoint(null)
    setEntryPoints([])

    api.getEntryPoints(selectedPipeline)
      .then(eps => {
        setEntryPoints(eps)
        if (eps.length > 0) {
          setSelectedEntryPoint(eps[0])
        }
      })
      .catch(() => {
        // Fallback: no entry points configured, show basic requirement field
        setEntryPoints([])
      })
      .finally(() => setLoadingEntryPoints(false))
  }, [selectedPipeline])

  // Reset field values when entry point changes (preserve requirement if it was prefilled)
  useEffect(() => {
    if (!selectedEntryPoint) return
    setFieldValues(prev => {
      const next: Record<string, string> = {}
      for (const field of selectedEntryPoint.inputFields) {
        // Keep existing value if the field was already filled (e.g. prefilled requirement)
        next[field.name] = prev[field.name] ?? ''
      }
      return next
    })
  }, [selectedEntryPoint])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!selectedPipeline) return

    setSubmitting(true)
    setSubmitError(null)

    try {
      const body: Parameters<typeof api.startRun>[0] = { configPath: selectedPipeline }
      if (dryRun) body.dryRun = true

      if (selectedEntryPoint) {
        body.entryPointId = selectedEntryPoint.id
        // Map field values to the appropriate request fields
        for (const field of selectedEntryPoint.inputFields) {
          const val = fieldValues[field.name]?.trim()
          if (!val) continue
          if (field.name === 'requirement') body.requirement = val
          else if (field.name === 'youtrackIssue') body.youtrackIssue = val
          else if (field.name === 'branchName') body.branchName = val
          else if (field.name === 'mrIid') body.mrIid = parseInt(val, 10)
        }
      } else {
        // No entry points — fallback to plain requirement
        const req = fieldValues['requirement']?.trim()
        if (req) body.requirement = req
      }

      const run = await api.startRun(body)
      if (run?.id) {
        navigate(`/runs/${run.id}`)
      } else {
        setSubmitError('Не удалось запустить: неожиданный ответ сервера')
      }
    } catch {
      setSubmitError('Не удалось запустить. Сервер работает?')
    } finally {
      setSubmitting(false)
    }
  }

  const hasRequiredEmpty = selectedEntryPoint?.inputFields.some(
    f => f.required && !fieldValues[f.name]?.trim()
  )

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader title="Пайплайны" description="Запуск нового пайплайна" />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
        {/* New Run Form */}
        <div>
          <h2 className="text-sm font-semibold text-white mb-4">Новый запуск</h2>
          <div className="bg-slate-900 border border-slate-800 rounded-xl p-6">
            {loadingPipelines ? (
              <div className="flex items-center gap-2 text-slate-400">
                <Loader2 className="w-4 h-4 animate-spin" />
                Загрузка пайплайнов...
              </div>
            ) : pipelinesError ? (
              <div className="flex items-center gap-2 text-red-400">
                <AlertCircle className="w-4 h-4" />
                {pipelinesError}
              </div>
            ) : (
              <form onSubmit={handleSubmit} className="space-y-5">
                {/* Pipeline selector */}
                <div>
                  <label className="block text-sm font-medium text-slate-300 mb-1.5">Пайплайн</label>
                  {pipelines.length === 0 ? (
                    <p className="text-slate-500 text-sm">Пайплайны не найдены.</p>
                  ) : (
                    <select
                      value={selectedPipeline}
                      onChange={e => setSelectedPipeline(e.target.value)}
                      className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                    >
                      {pipelines.map(p => (
                        <option key={p.path} value={p.path}>{p.pipelineName || p.name}</option>
                      ))}
                    </select>
                  )}
                </div>

                {/* Entry point selector */}
                {loadingEntryPoints ? (
                  <div className="flex items-center gap-2 text-slate-400 text-sm">
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    Загрузка точек входа...
                  </div>
                ) : entryPoints.length > 1 ? (
                  <div>
                    <label className="block text-sm font-medium text-slate-300 mb-1.5">Точка входа</label>
                    <div className="space-y-1.5">
                      {entryPoints.map(ep => (
                        <button
                          key={ep.id}
                          type="button"
                          onClick={() => setSelectedEntryPoint(ep)}
                          className={clsx(
                            'w-full text-left px-3 py-2.5 rounded-lg border text-sm transition-colors',
                            selectedEntryPoint?.id === ep.id
                              ? 'bg-blue-950/60 border-blue-600 text-blue-200'
                              : 'bg-slate-800 border-slate-700 text-slate-300 hover:border-slate-600 hover:text-white'
                          )}
                        >
                          <div className="font-medium">{ep.name}</div>
                          {ep.description && (
                            <div className="text-xs text-slate-500 mt-0.5">{ep.description}</div>
                          )}
                        </button>
                      ))}
                    </div>
                  </div>
                ) : entryPoints.length === 1 && entryPoints[0].name ? (
                  <div className="text-sm text-slate-400">
                    Точка входа: <span className="text-slate-200">{entryPoints[0].name}</span>
                    {entryPoints[0].description && (
                      <span className="text-slate-500 ml-1">— {entryPoints[0].description}</span>
                    )}
                  </div>
                ) : null}

                {/* Dynamic input fields */}
                {selectedEntryPoint?.inputFields.map(field => (
                  <div key={field.name}>
                    <label className="block text-sm font-medium text-slate-300 mb-1.5">
                      {field.label}
                      {!field.required && <span className="text-slate-500 ml-1">(необязательно)</span>}
                    </label>
                    {field.type === 'textarea' ? (
                      <textarea
                        value={fieldValues[field.name] ?? ''}
                        onChange={e => setFieldValues(prev => ({ ...prev, [field.name]: e.target.value }))}
                        rows={4}
                        placeholder={field.placeholder ?? 'Опишите, что должен сделать пайплайн...'}
                        className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent resize-none"
                      />
                    ) : (
                      <input
                        type={field.type}
                        value={fieldValues[field.name] ?? ''}
                        onChange={e => setFieldValues(prev => ({ ...prev, [field.name]: e.target.value }))}
                        placeholder={field.placeholder}
                        className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                      />
                    )}
                  </div>
                ))}

                {/* Fallback: no entry points and no fields — show a basic requirement textarea */}
                {!selectedEntryPoint && !loadingEntryPoints && entryPoints.length === 0 && (
                  <div>
                    <label className="block text-sm font-medium text-slate-300 mb-1.5">Требование</label>
                    <textarea
                      value={fieldValues['requirement'] ?? ''}
                      onChange={e => setFieldValues(prev => ({ ...prev, requirement: e.target.value }))}
                      rows={4}
                      placeholder="Опишите, что должен сделать пайплайн..."
                      className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent resize-none"
                    />
                  </div>
                )}

                {submitError && (
                  <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/50 border border-red-800 rounded-lg px-3 py-2">
                    <AlertCircle className="w-4 h-4 flex-shrink-0" />
                    {submitError}
                  </div>
                )}

                <label className="flex items-start gap-2.5 text-sm text-slate-300 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    checked={dryRun}
                    onChange={e => setDryRun(e.target.checked)}
                    disabled={submitting}
                    className="mt-0.5 rounded bg-slate-800 border-slate-600 text-blue-500 focus:ring-blue-500"
                  />
                  <span className="flex-1">
                    <span className="font-medium">Dry-run</span>
                    <span className="block text-xs text-slate-500 mt-0.5">
                      Блоки с side-effects (MR, CI, deploy) вернут mock-вывод без реальных внешних вызовов.
                    </span>
                  </span>
                </label>

                <button
                  type="submit"
                  disabled={submitting || !selectedPipeline || !!hasRequiredEmpty}
                  className={clsx(
                    'w-full flex items-center justify-center gap-2 disabled:bg-slate-700 disabled:text-slate-500 text-white font-medium py-2.5 px-4 rounded-lg transition-colors',
                    dryRun
                      ? 'bg-amber-600 hover:bg-amber-500'
                      : 'bg-blue-600 hover:bg-blue-500'
                  )}
                >
                  {submitting ? (
                    <><Loader2 className="w-4 h-4 animate-spin" />Запуск...</>
                  ) : (
                    <><Play className="w-4 h-4" />{dryRun ? 'Dry-run запуск' : 'Запустить'}</>
                  )}
                </button>
              </form>
            )}
          </div>
        </div>

        {/* Available Pipelines */}
        <div>
          <h2 className="text-sm font-semibold text-white mb-4">Доступные пайплайны</h2>
          <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
            {loadingPipelines ? (
              <div className="flex items-center gap-2 text-slate-400 p-6">
                <Loader2 className="w-4 h-4 animate-spin" />Loading...
              </div>
            ) : pipelinesError ? (
              <div className="flex items-center gap-2 text-red-400 p-6">
                <AlertCircle className="w-4 h-4" />{pipelinesError}
              </div>
            ) : pipelines.length === 0 ? (
              <div className="text-slate-500 p-6 text-sm">Пайплайны не настроены.</div>
            ) : (
              <ul className="divide-y divide-slate-800">
                {pipelines.map(p => (
                  <li key={p.path}>
                    <button
                      type="button"
                      onClick={() => setSelectedPipeline(p.path)}
                      className={clsx(
                        'w-full flex items-center justify-between px-5 py-3.5 text-left transition-colors group',
                        selectedPipeline === p.path
                          ? 'bg-blue-950/40 text-blue-300'
                          : 'hover:bg-slate-800/50 text-slate-300 hover:text-white'
                      )}
                    >
                      <div>
                        <span className="text-sm font-medium">{p.pipelineName || p.name}</span>
                        {p.description && <p className="text-xs text-slate-500 mt-0.5">{p.description}</p>}
                      </div>
                      <ChevronRight className={clsx(
                        'w-4 h-4 transition-colors flex-shrink-0',
                        selectedPipeline === p.path ? 'text-blue-400' : 'text-slate-600 group-hover:text-slate-400'
                      )} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

import { useState, useEffect } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { Play, AlertCircle, Loader2 } from 'lucide-react'
import { api } from '../../services/api'
import { EntryPoint } from '../../types'
import { runHref } from '../../utils/runHref'
import clsx from 'clsx'

type PipelineInfo = { path: string; name: string; pipelineName?: string; description?: string; error?: string }

export default function LaunchTab() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
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

  useEffect(() => {
    setLoadingPipelines(true)
    api.listPipelines()
      .then(data => {
        setPipelines(data)
        if (data.length > 0) setSelectedPipeline(data[0].path)
      })
      .catch(() => setPipelinesError('Не удалось загрузить пайплайны'))
      .finally(() => setLoadingPipelines(false))
  }, [])

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
        if (eps.length > 0) setSelectedEntryPoint(eps[0])
      })
      .catch(() => setEntryPoints([]))
      .finally(() => setLoadingEntryPoints(false))
  }, [selectedPipeline])

  useEffect(() => {
    if (!selectedEntryPoint) return
    setFieldValues(prev => {
      const next: Record<string, string> = {}
      for (const field of selectedEntryPoint.inputFields) {
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
        const extraInputs: Record<string, string> = {}
        for (const field of selectedEntryPoint.inputFields) {
          const val = fieldValues[field.name]?.trim()
          if (!val) continue
          if (field.name === 'requirement') body.requirement = val
          else if (field.name === 'youtrackIssue') body.youtrackIssue = val
          else if (field.name === 'branchName') body.branchName = val
          else if (field.name === 'mrIid') body.mrIid = parseInt(val, 10)
          else extraInputs[field.name] = val
        }
        if (Object.keys(extraInputs).length > 0) {
          body.inputs = extraInputs
          // Use first extra input value as display label if no explicit requirement
          if (!body.requirement) body.requirement = Object.values(extraInputs)[0]
        }
      } else {
        const req = fieldValues['requirement']?.trim()
        if (req) body.requirement = req
      }
      const run = await api.startRun(body)
      if (run?.id) navigate(runHref(run.id, pathname))
      else setSubmitError('Неожиданный ответ сервера')
    } catch {
      setSubmitError('Не удалось запустить. Сервер работает?')
    } finally {
      setSubmitting(false)
    }
  }

  const hasRequiredEmpty = selectedEntryPoint?.inputFields.some(
    f => f.required && !fieldValues[f.name]?.trim()
  )

  if (loadingPipelines) {
    return (
      <div className="flex items-center gap-2 text-slate-400 p-8">
        <Loader2 className="w-4 h-4 animate-spin" /> Загрузка пайплайнов...
      </div>
    )
  }

  if (pipelinesError) {
    return (
      <div className="flex items-center gap-2 text-red-400 p-8">
        <AlertCircle className="w-4 h-4" /> {pipelinesError}
      </div>
    )
  }

  if (pipelines.length === 0) {
    return (
      <div className="max-w-2xl mx-auto px-6 py-12 text-center text-slate-500">
        <p>Пайплайны не настроены для этого проекта.</p>
        <p className="text-xs mt-1">Добавьте YAML-конфиги в директорию проекта.</p>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 sm:px-6 py-8 space-y-6">
      {/* Pipeline selector (if multiple) */}
      {pipelines.length > 1 && (
        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">
            Пайплайн
          </label>
          <div className="flex flex-wrap gap-2">
            {pipelines.map(p => (
              <button
                key={p.path}
                type="button"
                onClick={() => setSelectedPipeline(p.path)}
                className={clsx(
                  'px-3 py-1.5 rounded-lg text-sm font-medium border transition-colors',
                  selectedPipeline === p.path
                    ? 'bg-blue-950/60 border-blue-600 text-blue-200'
                    : 'bg-slate-800 border-slate-700 text-slate-300 hover:border-slate-500 hover:text-white'
                )}
              >
                {p.pipelineName || p.name}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Entry point cards */}
      {loadingEntryPoints ? (
        <div className="flex items-center gap-2 text-slate-400 text-sm">
          <Loader2 className="w-3.5 h-3.5 animate-spin" /> Загрузка стадий...
        </div>
      ) : entryPoints.length > 1 ? (
        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">
            Выберите стадию
          </label>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {entryPoints.map(ep => (
              <button
                key={ep.id}
                type="button"
                onClick={() => setSelectedEntryPoint(ep)}
                className={clsx(
                  'text-left px-4 py-3.5 rounded-xl border transition-all',
                  selectedEntryPoint?.id === ep.id
                    ? 'bg-blue-950/60 border-blue-600 text-blue-200 ring-1 ring-blue-600/40'
                    : 'bg-slate-900 border-slate-700 text-slate-300 hover:border-slate-500 hover:text-white'
                )}
              >
                <div className="font-medium text-sm">{ep.name}</div>
                {ep.description && (
                  <div className="text-xs text-slate-500 mt-1 leading-relaxed">{ep.description}</div>
                )}
              </button>
            ))}
          </div>
        </div>
      ) : null}

      {/* Launch form */}
      <form onSubmit={handleSubmit} className="bg-slate-900 border border-slate-800 rounded-xl p-5 space-y-4">
        {entryPoints.length === 1 && (
          <div>
            <p className="text-sm font-medium text-slate-300">{entryPoints[0].name}</p>
            {entryPoints[0].description && (
              <p className="text-xs text-slate-500 mt-0.5">{entryPoints[0].description}</p>
            )}
          </div>
        )}

        {selectedEntryPoint?.inputFields.map(field => (
          <div key={field.name}>
            <label className="block text-sm font-medium text-slate-300 mb-1.5">
              {field.label}
              {!field.required && <span className="text-slate-500 ml-1 font-normal">(необязательно)</span>}
            </label>
            {field.type === 'textarea' ? (
              <textarea
                value={fieldValues[field.name] ?? ''}
                onChange={e => setFieldValues(prev => ({ ...prev, [field.name]: e.target.value }))}
                rows={4}
                placeholder={field.placeholder ?? 'Опишите задачу...'}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
              />
            ) : (
              <input
                type={field.type}
                value={fieldValues[field.name] ?? ''}
                onChange={e => setFieldValues(prev => ({ ...prev, [field.name]: e.target.value }))}
                placeholder={field.placeholder}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            )}
          </div>
        ))}

        {!selectedEntryPoint && !loadingEntryPoints && entryPoints.length === 0 && (
          <div>
            <label className="block text-sm font-medium text-slate-300 mb-1.5">Требование</label>
            <textarea
              value={fieldValues['requirement'] ?? ''}
              onChange={e => setFieldValues(prev => ({ ...prev, requirement: e.target.value }))}
              rows={4}
              placeholder="Опишите, что должен сделать пайплайн..."
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
            />
          </div>
        )}

        {submitError && (
          <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/50 border border-red-800 rounded-lg px-3 py-2">
            <AlertCircle className="w-4 h-4 flex-shrink-0" /> {submitError}
          </div>
        )}

        <div className="flex items-center justify-between gap-4 pt-1">
          <label className="flex items-center gap-2 text-sm text-slate-400 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={dryRun}
              onChange={e => setDryRun(e.target.checked)}
              disabled={submitting}
              className="rounded bg-slate-800 border-slate-600 text-blue-500 focus:ring-blue-500"
            />
            Dry-run
          </label>

          <button
            type="submit"
            disabled={submitting || !selectedPipeline || !!hasRequiredEmpty}
            className={clsx(
              'flex items-center gap-2 text-white font-medium py-2.5 px-6 rounded-lg transition-colors disabled:bg-slate-700 disabled:text-slate-500',
              dryRun ? 'bg-amber-600 hover:bg-amber-500' : 'bg-blue-600 hover:bg-blue-500'
            )}
          >
            {submitting
              ? <><Loader2 className="w-4 h-4 animate-spin" />Запуск...</>
              : <><Play className="w-4 h-4" />{dryRun ? 'Dry-run' : 'Запустить'}</>
            }
          </button>
        </div>
      </form>
    </div>
  )
}

import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Play, AlertCircle, Loader2, ChevronRight } from 'lucide-react'
import { api } from '../services/api'
import clsx from 'clsx'

type InputMode = 'requirement' | 'youtrack'

type PipelineInfo = { path: string; name: string; pipelineName?: string; description?: string; error?: string }

export default function HomePage() {
  const navigate = useNavigate()
  const [pipelines, setPipelines] = useState<PipelineInfo[]>([])
  const [loadingPipelines, setLoadingPipelines] = useState(true)
  const [pipelinesError, setPipelinesError] = useState<string | null>(null)

  // New run form state
  const [selectedPipeline, setSelectedPipeline] = useState('')
  const [inputMode, setInputMode] = useState<InputMode>('requirement')
  const [requirement, setRequirement] = useState('')
  const [youtrackIssue, setYoutrackIssue] = useState('')
  const [fromBlock, setFromBlock] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

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

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!selectedPipeline) return

    setSubmitting(true)
    setSubmitError(null)

    try {
      const body: Parameters<typeof api.startRun>[0] = {
        configPath: selectedPipeline,
      }
      if (inputMode === 'requirement' && requirement.trim()) {
        body.requirement = requirement.trim()
      }
      if (inputMode === 'youtrack' && youtrackIssue.trim()) {
        body.youtrackIssue = youtrackIssue.trim()
      }
      if (fromBlock.trim()) {
        body.fromBlock = fromBlock.trim()
      }

      const run = await api.startRun(body)
      if (run?.id) {
        navigate(`/run/${run.id}`)
      } else {
        setSubmitError('Не удалось запустить: неожиданный ответ')
      }
    } catch {
      setSubmitError('Не удалось запустить. Сервер работает?')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
        {/* New Run Form */}
        <div>
          <h2 className="text-lg font-semibold text-white mb-4">Новый запуск</h2>
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
                  <label className="block text-sm font-medium text-slate-300 mb-1.5">
                    Пайплайн
                  </label>
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

                {/* Input mode toggle */}
                <div>
                  <label className="block text-sm font-medium text-slate-300 mb-1.5">
                    Источник ввода
                  </label>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => setInputMode('requirement')}
                      className={clsx(
                        'flex-1 py-1.5 px-3 rounded-md text-sm font-medium border transition-colors',
                        inputMode === 'requirement'
                          ? 'bg-blue-600 border-blue-500 text-white'
                          : 'bg-slate-800 border-slate-700 text-slate-400 hover:text-white'
                      )}
                    >
                      Текст требования
                    </button>
                    <button
                      type="button"
                      onClick={() => setInputMode('youtrack')}
                      className={clsx(
                        'flex-1 py-1.5 px-3 rounded-md text-sm font-medium border transition-colors',
                        inputMode === 'youtrack'
                          ? 'bg-blue-600 border-blue-500 text-white'
                          : 'bg-slate-800 border-slate-700 text-slate-400 hover:text-white'
                      )}
                    >
                      Задача YouTrack
                    </button>
                  </div>
                </div>

                {/* Requirement text */}
                {inputMode === 'requirement' && (
                  <div>
                    <label className="block text-sm font-medium text-slate-300 mb-1.5">
                      Требование
                    </label>
                    <textarea
                      value={requirement}
                      onChange={e => setRequirement(e.target.value)}
                      rows={4}
                      placeholder="Опишите, что должен сделать пайплайн..."
                      className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent resize-none"
                    />
                  </div>
                )}

                {/* YouTrack issue */}
                {inputMode === 'youtrack' && (
                  <div>
                    <label className="block text-sm font-medium text-slate-300 mb-1.5">
                      ID задачи YouTrack
                    </label>
                    <input
                      type="text"
                      value={youtrackIssue}
                      onChange={e => setYoutrackIssue(e.target.value)}
                      placeholder="e.g. PROJ-123"
                      className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                    />
                  </div>
                )}

                {/* From block (optional) */}
                <div>
                  <label className="block text-sm font-medium text-slate-300 mb-1.5">
                    Начать с блока <span className="text-slate-500">(необязательно)</span>
                  </label>
                  <input
                    type="text"
                    value={fromBlock}
                    onChange={e => setFromBlock(e.target.value)}
                    placeholder="ID блока для старта..."
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  />
                </div>

                {submitError && (
                  <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/50 border border-red-800 rounded-lg px-3 py-2">
                    <AlertCircle className="w-4 h-4 flex-shrink-0" />
                    {submitError}
                  </div>
                )}

                <button
                  type="submit"
                  disabled={submitting || !selectedPipeline}
                  className="w-full flex items-center justify-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white font-medium py-2.5 px-4 rounded-lg transition-colors"
                >
                  {submitting ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      Запуск...
                    </>
                  ) : (
                    <>
                      <Play className="w-4 h-4" />
                      Запустить
                    </>
                  )}
                </button>
              </form>
            )}
          </div>
        </div>

        {/* Available Pipelines */}
        <div>
          <h2 className="text-lg font-semibold text-white mb-4">Доступные пайплайны</h2>
          <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
            {loadingPipelines ? (
              <div className="flex items-center gap-2 text-slate-400 p-6">
                <Loader2 className="w-4 h-4 animate-spin" />
                Loading...
              </div>
            ) : pipelinesError ? (
              <div className="flex items-center gap-2 text-red-400 p-6">
                <AlertCircle className="w-4 h-4" />
                {pipelinesError}
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

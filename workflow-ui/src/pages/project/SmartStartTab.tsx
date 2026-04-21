import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Sparkles, AlertCircle, Loader2, Play, ChevronDown } from 'lucide-react'
import { api } from '../../services/api'
import clsx from 'clsx'

type DetectResult = Awaited<ReturnType<typeof api.smartDetect>>
type PipelineInfo = { path: string; name: string; pipelineName?: string }

const ENTRY_POINT_OPTIONS = [
  { id: 'from_scratch',  label: 'С нуля (анализ → задачи → кодинг)' },
  { id: 'tasks_exist',   label: 'Задачи уже есть → кодинг' },
  { id: 'branch_exists', label: 'Ветка существует → кодинг' },
  { id: 'mr_open',       label: 'Открытый MR/PR → ревью' },
  { id: 'bug_fix',       label: 'Баг-фикс' },
]

const confidenceColor = (c: number) =>
  c >= 0.8 ? 'text-emerald-400' : c >= 0.6 ? 'text-amber-400' : 'text-red-400'

export default function SmartStartTab() {
  const navigate = useNavigate()

  const [pipelines, setPipelines] = useState<PipelineInfo[]>([])
  const [selectedPipeline, setSelectedPipeline] = useState('')

  const [input, setInput] = useState('')
  const [analyzing, setAnalyzing] = useState(false)
  const [result, setResult] = useState<DetectResult | null>(null)
  const [analyzeError, setAnalyzeError] = useState<string | null>(null)

  const [clarificationAnswer, setClarificationAnswer] = useState('')
  const [overrideEntryPoint, setOverrideEntryPoint] = useState<string | null>(null)

  const [launching, setLaunching] = useState(false)
  const [launchError, setLaunchError] = useState<string | null>(null)

  useEffect(() => {
    api.listPipelines()
      .then(data => {
        setPipelines(data)
        if (data.length > 0) setSelectedPipeline(data[0].path)
      })
      .catch(() => {})
  }, [])

  const doAnalyze = async (rawInput: string) => {
    setAnalyzing(true)
    setAnalyzeError(null)
    try {
      const r = await api.smartDetect({
        rawInput,
        configPath: selectedPipeline || undefined,
      })
      setResult(r)
      setOverrideEntryPoint(null)
    } catch (e) {
      setAnalyzeError(e instanceof Error ? e.message : 'Ошибка анализа')
    } finally {
      setAnalyzing(false)
    }
  }

  const handleAnalyze = () => { if (input.trim()) doAnalyze(input.trim()) }

  const handleClarify = () => {
    if (!clarificationAnswer.trim() || !result) return
    const combined = `${input}\n\nУточнение: ${clarificationAnswer}`
    setClarificationAnswer('')
    doAnalyze(combined)
  }

  const handleLaunch = async () => {
    if (!result || !selectedPipeline) return
    const entryPointId = overrideEntryPoint ?? result.suggested.entryPointId
    const detectedInputs = result.detectedInputs as Record<string, string>

    setLaunching(true)
    setLaunchError(null)
    try {
      const body: Parameters<typeof api.startRun>[0] = { configPath: selectedPipeline }
      body.entryPointId = entryPointId
      if (detectedInputs.issueId) {
        body.youtrackIssue = String(detectedInputs.issueId)
      } else {
        body.requirement = input.trim()
      }
      const run = await api.startRun(body)
      if (run?.id) navigate(`/runs/${run.id}`)
      else setLaunchError('Неожиданный ответ сервера')
    } catch (e) {
      setLaunchError(e instanceof Error ? e.message : 'Не удалось запустить. Сервер работает?')
    } finally {
      setLaunching(false)
    }
  }

  const activeEntryPointId = overrideEntryPoint ?? result?.suggested.entryPointId

  return (
    <div className="max-w-2xl mx-auto px-4 sm:px-6 py-8 space-y-5">
      {/* Pipeline selector */}
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

      {/* Input area */}
      <div className="space-y-3">
        <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide">
          Что нужно сделать?
        </label>
        <textarea
          value={input}
          onChange={e => setInput(e.target.value)}
          rows={5}
          placeholder={
            'Вставьте ссылку на задачу, стектрейс или опишите что нужно сделать.\n\n' +
            'Примеры:\n  • https://youtrack.company.com/issue/PROJ-42\n' +
            '  • NullPointerException at com.example.Service:42\n' +
            '  • Добавить авторизацию через OAuth'
          }
          className="w-full bg-slate-900 border border-slate-700 rounded-xl px-4 py-3 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
          onKeyDown={e => { if (e.key === 'Enter' && e.metaKey) handleAnalyze() }}
        />
        <button
          type="button"
          onClick={handleAnalyze}
          disabled={analyzing || !input.trim()}
          className="flex items-center gap-2 bg-slate-700 hover:bg-slate-600 disabled:bg-slate-800 disabled:text-slate-600 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
        >
          {analyzing
            ? <><Loader2 className="w-4 h-4 animate-spin" /> Анализирую...</>
            : <><Sparkles className="w-4 h-4" /> Анализировать</>}
        </button>
      </div>

      {analyzeError && (
        <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/50 border border-red-800 rounded-lg px-3 py-2">
          <AlertCircle className="w-4 h-4 flex-shrink-0" /> {analyzeError}
        </div>
      )}

      {/* Result card */}
      {result && !analyzing && (
        <div className="bg-slate-900 border border-slate-700 rounded-xl p-5 space-y-4">
          {/* Intent + confidence */}
          <div className="flex items-start justify-between gap-4">
            <div>
              <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">Определено</p>
              <p className="text-white font-medium capitalize">{result.suggested.intentLabel}</p>
              <p className="text-xs text-slate-500 mt-1">{result.explanation}</p>
            </div>
            <div className="text-right shrink-0">
              <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">Уверенность</p>
              <p className={clsx('text-lg font-semibold', confidenceColor(result.suggested.confidence))}>
                {Math.round(result.suggested.confidence * 100)}%
              </p>
            </div>
          </div>

          {/* Clarification question */}
          {result.clarificationQuestion && (
            <div className="bg-amber-950/30 border border-amber-800/50 rounded-lg p-3 space-y-2">
              <p className="text-amber-300 text-sm">{result.clarificationQuestion}</p>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={clarificationAnswer}
                  onChange={e => setClarificationAnswer(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') handleClarify() }}
                  placeholder="Ваш ответ..."
                  className="flex-1 bg-slate-800 border border-slate-600 rounded-lg px-3 py-1.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
                <button
                  type="button"
                  onClick={handleClarify}
                  disabled={!clarificationAnswer.trim() || analyzing}
                  className="px-3 py-1.5 text-sm bg-amber-700 hover:bg-amber-600 disabled:bg-slate-700 disabled:text-slate-500 text-white rounded-lg transition-colors"
                >
                  Уточнить
                </button>
              </div>
            </div>
          )}

          {/* Entry point selector */}
          <div>
            <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">
              Flow для запуска
            </label>
            <div className="relative">
              <select
                value={activeEntryPointId ?? ''}
                onChange={e => setOverrideEntryPoint(e.target.value || null)}
                className="w-full appearance-none bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 pr-8"
              >
                {ENTRY_POINT_OPTIONS.map(opt => (
                  <option key={opt.id} value={opt.id}>{opt.label}</option>
                ))}
              </select>
              <ChevronDown className="absolute right-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
            </div>
            {overrideEntryPoint && overrideEntryPoint !== result.suggested.entryPointId && (
              <p className="text-xs text-amber-400 mt-1">
                Изменено вручную (предложено: {result.suggested.entryPointId})
              </p>
            )}
          </div>

          {launchError && (
            <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/50 border border-red-800 rounded-lg px-3 py-2">
              <AlertCircle className="w-4 h-4 flex-shrink-0" /> {launchError}
            </div>
          )}

          <button
            type="button"
            onClick={handleLaunch}
            disabled={launching || !activeEntryPointId || !selectedPipeline}
            className="w-full flex items-center justify-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white font-medium py-2.5 rounded-lg transition-colors"
          >
            {launching
              ? <><Loader2 className="w-4 h-4 animate-spin" /> Запуск...</>
              : <><Play className="w-4 h-4" /> Запустить</>}
          </button>
        </div>
      )}
    </div>
  )
}

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { AlertCircle, ChevronDown, FileText, Loader2, Plus } from 'lucide-react'
import { api } from '../../services/api'
import { useBlockRegistry } from '../../hooks/useBlockRegistry'
import { usePipelineEditor } from '../../hooks/usePipelineEditor'
import { BlockConfigDto, BlockRegistryEntry, ValidationError } from '../../types'
import { blockTypeLabelWithCode } from '../../utils/blockLabels'
import Toolbar from './Toolbar'
import Canvas from './Canvas'
import BlockPalette from './BlockPalette'
import SidePanel from './SidePanel'
import PipelineSettingsModal from './PipelineSettingsModal'
import CreationWizard from './Wizard/CreationWizard'
import { effectivePhase, PHASE_LABEL, phaseOrder, phaseStripeClass } from '../../utils/phaseColors'

interface PipelineInfo {
  path: string
  name: string
  pipelineName?: string
  description?: string
  error?: string
}

export function PipelineEditor() {
  const { slug } = useParams<{ slug: string }>()
  const projectSlug = slug ?? 'default'

  const editor = usePipelineEditor()
  const { registry, byType, loading: registryLoading, error: registryError } = useBlockRegistry()

  const [pipelines, setPipelines] = useState<PipelineInfo[]>([])
  const [pipelinesLoading, setPipelinesLoading] = useState(false)
  const [showSettings, setShowSettings] = useState(false)
  const [showNewPipeline, setShowNewPipeline] = useState(false)
  const [showWizard, setShowWizard] = useState(false)
  const [selectedBlockId, setSelectedBlockId] = useState<string | null>(null)
  const [showAddPicker, setShowAddPicker] = useState<{ afterBlockId: string | null } | null>(null)
  const addPickerRef = useRef<HTMLDivElement>(null)

  // Beforeunload guard for unsaved changes
  useEffect(() => {
    if (!editor.dirty) return
    const handler = (e: BeforeUnloadEvent) => { e.preventDefault(); return '' }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [editor.dirty])

  // Ctrl/Cmd+Z → undo. Skip when typing inside a textarea so users can use native
  // multi-line undo there; single-line inputs are controlled and rerender cleanly
  // when our state rolls back, so app-level undo is safe (and more useful) there.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey)) return
      if (e.key !== 'z' && e.key !== 'Z') return
      if (e.shiftKey) return
      const t = e.target as HTMLElement | null
      if (t && t.tagName === 'TEXTAREA') return
      if (!editor.canUndo) return
      e.preventDefault()
      editor.undo()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [editor.canUndo, editor.undo])

  // Load pipelines list
  useEffect(() => {
    setPipelinesLoading(true)
    api.listPipelines()
      .then(list => {
        setPipelines(list)
        if (list.length > 0 && !editor.configPath) {
          editor.setConfigPath(list[0].path)
        }
      })
      .catch(() => {})
      .finally(() => setPipelinesLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Listen for the "+" event from BlockNode to show palette popover
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent<{ afterBlockId: string }>).detail
      setShowAddPicker({ afterBlockId: detail.afterBlockId })
    }
    window.addEventListener('pipeline-editor:add-after', handler)
    return () => window.removeEventListener('pipeline-editor:add-after', handler)
  }, [])

  // Close popover on outside click or Escape. Capture phase so react-flow's
  // pointer-event handlers can't swallow propagation before we see it.
  useEffect(() => {
    if (!showAddPicker) return
    const handler = (e: MouseEvent) => {
      if (addPickerRef.current && !addPickerRef.current.contains(e.target as Node)) {
        setShowAddPicker(null)
      }
    }
    const escHandler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowAddPicker(null)
    }
    document.addEventListener('mousedown', handler, true)
    document.addEventListener('keydown', escHandler)
    return () => {
      document.removeEventListener('mousedown', handler, true)
      document.removeEventListener('keydown', escHandler)
    }
  }, [showAddPicker])

  const selectedBlock = useMemo(() => {
    if (!selectedBlockId || !editor.current) return null
    return editor.current.pipeline?.find(b => b.id === selectedBlockId) ?? null
  }, [selectedBlockId, editor.current])

  const selectedBlockErrors: ValidationError[] = useMemo(() => {
    if (!selectedBlockId) return []
    return editor.errors.filter(e => e.blockId === selectedBlockId)
  }, [selectedBlockId, editor.errors])

  const presentPhases = useMemo(() => {
    const out = new Set<import('../../types').Phase>()
    for (const b of editor.current?.pipeline ?? []) {
      const p = effectivePhase(b, byType[b.block])
      if (p !== 'ANY') out.add(p)
    }
    return out
  }, [editor.current, byType])

  const addBlockFromRegistry = useCallback((entry: BlockRegistryEntry, afterBlockId?: string | null) => {
    if (!editor.current) return
    const existingIds = new Set((editor.current.pipeline ?? []).map(b => b.id))
    let id = entry.type
    let n = 1
    while (existingIds.has(id)) id = `${entry.type}_${++n}`
    const block: BlockConfigDto = {
      id,
      block: entry.type,
      depends_on: afterBlockId ? [afterBlockId] : [],
      config: {},
      enabled: true,
    }
    editor.addBlock(block, afterBlockId ? { afterBlockId } : undefined)
    setSelectedBlockId(id)
  }, [editor])

  const validate = async () => {
    // If dirty, save-as-validate via direct call; otherwise validate the on-disk file
    await editor.validate()
  }

  const save = async () => { await editor.save() }

  // ── Render ────────────────────────────────────────────────────────────────

  if (registryError) {
    return (
      <div className="flex items-center gap-2 text-red-400 p-6 text-sm">
        <AlertCircle className="w-4 h-4" /> Ошибка загрузки реестра блоков: {registryError}
      </div>
    )
  }

  return (
    <div className="flex flex-col h-[calc(100vh-130px)]">
      {/* Top toolbar */}
      <Toolbar
        projectSlug={projectSlug}
        pipelineName={editor.current?.name ?? ''}
        onPipelineName={v => editor.patchConfig({ name: v })}
        description={editor.current?.description ?? ''}
        onDescription={v => editor.patchConfig({ description: v })}
        dirty={editor.dirty}
        saving={editor.saving}
        validating={editor.validating}
        validatedClean={editor.validatedClean}
        errorCount={editor.errors.filter(e => !e.severity || e.severity === 'ERROR').length}
        canUndo={editor.canUndo}
        presentPhases={presentPhases}
        phaseCheckEnabled={editor.current?.phase_check !== false}
        onUndo={editor.undo}
        onValidate={validate}
        onSave={save}
        onOpenSettings={() => setShowSettings(true)}
      />

      {/* Pipeline picker bar */}
      <div className="bg-slate-900/50 border-b border-slate-800 px-4 py-2 flex items-center gap-3">
        <span className="text-xs text-slate-400 uppercase tracking-wide">Конфиг</span>
        <div className="relative">
          <select
            data-testid="pipeline-picker"
            value={editor.configPath ?? ''}
            onChange={e => {
              if (editor.dirty && !confirm('Несохранённые изменения. Переключить пайплайн?')) return
              editor.setConfigPath(e.target.value || null)
              setSelectedBlockId(null)
            }}
            className="appearance-none bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 pr-7 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            <option value="">— Выбрать —</option>
            {pipelines.map(p => (
              <option key={p.path} value={p.path}>{p.pipelineName || p.name}</option>
            ))}
          </select>
          <ChevronDown className="absolute right-2 top-2 w-3.5 h-3.5 text-slate-500 pointer-events-none" />
        </div>
        <button
          type="button"
          data-testid="pipeline-new"
          onClick={() => setShowNewPipeline(true)}
          className="text-xs flex items-center gap-1 text-blue-400 hover:text-blue-300 ml-2"
        >
          <Plus className="w-3 h-3" /> Новый пайплайн
        </button>

        {pipelinesLoading && <Loader2 className="w-3.5 h-3.5 animate-spin text-slate-500" />}

        {editor.saveError && (
          <span className="ml-auto text-xs text-red-300 flex items-center gap-1">
            <AlertCircle className="w-3.5 h-3.5" /> {editor.saveError}
          </span>
        )}
      </div>

      {/* Main editor */}
      <div className="flex flex-1 min-h-0">
        {/* Palette */}
        {!registryLoading && registry.length > 0 && (
          <BlockPalette registry={registry} onAdd={entry => addBlockFromRegistry(entry, null)} />
        )}

        {/* Canvas */}
        <div className="flex-1 relative bg-slate-950">
          {editor.loading && (
            <div className="absolute inset-0 flex items-center justify-center text-slate-500 z-10 bg-slate-950/70">
              <Loader2 className="w-5 h-5 animate-spin" />
            </div>
          )}
          {editor.loadError && (
            <div className="absolute inset-0 flex items-center justify-center text-red-400 p-8 z-10 bg-slate-950/70">
              <div className="flex items-center gap-2"><AlertCircle className="w-4 h-4" /> {editor.loadError}</div>
            </div>
          )}
          {!editor.current && !editor.loading && !editor.loadError && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 text-slate-500">
              <FileText className="w-8 h-8" />
              <p className="text-sm">Выберите пайплайн или создайте новый.</p>
            </div>
          )}
          {editor.current && (
            <Canvas
              config={editor.current}
              selectedBlockId={selectedBlockId}
              errors={editor.errors}
              byType={byType}
              onSelectBlock={setSelectedBlockId}
              onConnectDependsOn={(s, t) => editor.setDependsOn(s, t, true)}
              onDeleteEdge={(s, t) => editor.setDependsOn(s, t, false)}
            />
          )}

          {/* Add-block popover */}
          {showAddPicker && (() => {
            const afterId = showAddPicker.afterBlockId
            const afterBlock = afterId
              ? editor.current?.pipeline?.find(b => b.id === afterId)
              : null
            const afterPhase = afterBlock
              ? effectivePhase(afterBlock, byType[afterBlock.block])
              : null
            const allowed = afterPhase
              ? registry.filter(e => {
                  const p = e.metadata.phase
                  if (p === 'ANY' || afterPhase === 'ANY') return true
                  return phaseOrder(p) >= phaseOrder(afterPhase)
                })
              : registry
            const filtered = afterPhase && allowed.length < registry.length
            return (
              <div ref={addPickerRef}
                data-testid="add-block-popover"
                className="absolute top-4 left-1/2 -translate-x-1/2 z-30 bg-slate-900 border border-slate-700 rounded-lg shadow-xl p-2 max-h-[60vh] overflow-y-auto w-72">
                <div className="text-xs text-slate-400 px-2 pb-2 border-b border-slate-800 mb-2 flex items-center gap-1.5">
                  {filtered && afterPhase && (
                    <span
                      className={`w-2 h-2 rounded-sm ${phaseStripeClass(afterPhase)}`}
                      title={PHASE_LABEL[afterPhase]}
                    />
                  )}
                  <span>
                    {filtered
                      ? `После фазы ${afterPhase!.toLowerCase()} — допустимы:`
                      : 'Тип блока для добавления'}
                  </span>
                </div>
                <div className="space-y-0.5">
                  {allowed.map(e => (
                    <button
                      key={e.type}
                      type="button"
                      data-testid={`add-after-${e.type}`}
                      onClick={() => {
                        addBlockFromRegistry(e, showAddPicker.afterBlockId)
                        setShowAddPicker(null)
                      }}
                      className="w-full text-left px-2 py-1.5 rounded text-xs bg-slate-800/40 hover:bg-blue-900/40 border border-slate-800 hover:border-blue-700 text-slate-200 hover:text-blue-200 flex items-center gap-2"
                    >
                      <span
                        aria-hidden
                        className={`w-1.5 h-4 rounded-sm flex-shrink-0 ${phaseStripeClass(e.metadata.phase)}`}
                        title={PHASE_LABEL[e.metadata.phase]}
                      />
                      <span className="flex-1 min-w-0">
                        <div className="font-medium">{blockTypeLabelWithCode(e.type)}</div>
                        <div className="font-mono text-[10px] text-slate-500">{e.metadata.label}</div>
                      </span>
                    </button>
                  ))}
                  {allowed.length === 0 && (
                    <div className="text-[11px] text-slate-500 px-2 py-3 text-center">
                      Нет блоков, совместимых с фазой <code>{afterPhase?.toLowerCase()}</code>.
                    </div>
                  )}
                </div>
              </div>
            )
          })()}
        </div>

        {/* Side panel */}
        {selectedBlock && editor.current && (
          <SidePanel
            block={selectedBlock}
            registryEntry={byType[selectedBlock.block]}
            config={editor.current}
            byType={byType}
            errors={selectedBlockErrors}
            onClose={() => setSelectedBlockId(null)}
            onPatch={patch => editor.patchBlock(selectedBlock.id, patch)}
            onRename={(oldId, newId) => {
              const ok = editor.renameBlock(oldId, newId)
              if (ok) setSelectedBlockId(newId)
              return ok
            }}
            onDelete={() => {
              if (confirm(`Удалить блок «${selectedBlock.id}»?`)) {
                editor.removeBlock(selectedBlock.id)
                setSelectedBlockId(null)
              }
            }}
          />
        )}
      </div>

      {/* Pipeline settings modal */}
      {showSettings && editor.current && (
        <PipelineSettingsModal
          config={editor.current}
          onChange={editor.patchConfig}
          onClose={() => setShowSettings(false)}
        />
      )}

      {/* New pipeline modal */}
      {showNewPipeline && (
        <NewPipelineModal
          onClose={() => setShowNewPipeline(false)}
          onCreated={p => {
            setPipelines(list => [...list, p])
            editor.setConfigPath(p.path)
            setShowNewPipeline(false)
          }}
          onCustom={() => {
            setShowNewPipeline(false)
            setShowWizard(true)
          }}
        />
      )}

      {/* Creation Wizard (full-screen modal) */}
      {showWizard && (
        <CreationWizard
          registry={registry}
          byType={byType}
          onCancel={() => setShowWizard(false)}
          onCreated={p => {
            setPipelines(list => [...list, p])
            editor.setConfigPath(p.path)
            setShowWizard(false)
          }}
        />
      )}
    </div>
  )
}

type PipelineTemplate = 'empty' | 'standard' | 'custom'

function NewPipelineModal({ onClose, onCreated, onCustom }: {
  onClose: () => void
  onCreated: (info: { path: string; name: string; pipelineName: string }) => void
  /**
   * Invoked when the user picks the 'custom' template — the parent should close
   * this modal and open the {@code CreationWizard} (PR-3, 2026-05-07). The
   * wizard takes its own slug/displayName/description in its pre-step, so we
   * intentionally don't forward those.
   */
  onCustom: () => void
}) {
  const [slug, setSlug] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [description, setDescription] = useState('')
  const [template, setTemplate] = useState<PipelineTemplate>('standard')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    setError(null)
    // Custom template → hand off to the wizard. The wizard collects its own
    // name fields (it gives users richer phase-by-phase composition), so the
    // ones in this modal are ignored for that path.
    if (template === 'custom') {
      onCustom()
      return
    }
    if (!/^[a-z0-9][a-z0-9-]*$/.test(slug)) {
      setError('Slug должен быть kebab-case (a-z, 0-9, -)')
      return
    }
    if (!displayName.trim()) {
      setError('Название обязательно')
      return
    }
    setSubmitting(true)
    try {
      // Both 'empty' and 'standard' call the same createPipeline endpoint —
      // after the in-repo feature.yaml migration it IS the canonical 6-phase skeleton.
      const result = await api.createPipeline({ slug, displayName, description })
      onCreated({ path: result.path, name: result.name, pipelineName: result.pipelineName })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось создать')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center" onClick={onClose}>
      <div className="bg-slate-900 border border-slate-700 rounded-xl w-96 p-5 space-y-4" onClick={e => e.stopPropagation()}>
        <h2 className="text-sm font-semibold text-slate-100">Новый пайплайн</h2>
        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1">Slug (имя файла)</label>
          <input
            data-testid="new-pipeline-slug"
            type="text"
            value={slug}
            onChange={e => setSlug(e.target.value.toLowerCase())}
            placeholder="my-pipeline"
            className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <p className="text-[10px] text-slate-500 mt-1">Будет создан файл &lt;configDir&gt;/{slug || '<slug>'}.yaml</p>
        </div>
        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1">Название</label>
          <input
            data-testid="new-pipeline-name"
            type="text"
            value={displayName}
            onChange={e => setDisplayName(e.target.value)}
            placeholder="My Pipeline"
            className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1">Описание</label>
          <textarea
            value={description}
            onChange={e => setDescription(e.target.value)}
            rows={3}
            className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 resize-y"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1">Шаблон</label>
          <div className="space-y-1.5" data-testid="new-pipeline-template">
            <TemplateRadio
              value="empty"
              current={template}
              onSelect={setTemplate}
              label="Пустой"
              hint="Минимальный скелет — добавишь блоки сам."
            />
            <TemplateRadio
              value="standard"
              current={template}
              onSelect={setTemplate}
              label="Standard feature (6 фаз)"
              hint="intake → analyze → implement → verify → publish → release."
            />
            <TemplateRadio
              value="custom"
              current={template}
              onSelect={setTemplate}
              label="Custom (мастер по фазам)"
              hint="Пошаговый мастер: фаза за фазой, рекомендуемые блоки."
            />
          </div>
        </div>
        {error && (
          <div className="flex items-center gap-1.5 text-xs text-red-400">
            <AlertCircle className="w-3.5 h-3.5" /> {error}
          </div>
        )}
        <div className="flex justify-end gap-2">
          <button onClick={onClose} className="text-xs text-slate-400 hover:text-slate-200 px-3 py-1.5">
            Отмена
          </button>
          <button
            onClick={submit}
            disabled={submitting}
            data-testid="new-pipeline-submit"
            className="bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 text-white text-xs px-3 py-1.5 rounded-lg flex items-center gap-1"
          >
            {submitting && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            Создать
          </button>
        </div>
      </div>
    </div>
  )
}

function TemplateRadio({ value, current, onSelect, label, hint, disabled }: {
  value: PipelineTemplate
  current: PipelineTemplate
  onSelect: (v: PipelineTemplate) => void
  label: string
  hint: string
  disabled?: boolean
}) {
  const checked = current === value
  return (
    <label
      data-testid={`new-pipeline-template-${value}`}
      className={
        'flex items-start gap-2 px-2 py-1.5 rounded border text-xs cursor-pointer ' +
        (disabled
          ? 'border-slate-800 bg-slate-950/40 text-slate-600 cursor-not-allowed'
          : checked
            ? 'border-blue-700 bg-blue-950/30 text-slate-100'
            : 'border-slate-800 hover:border-slate-700 text-slate-300')
      }
    >
      <input
        type="radio"
        name="new-pipeline-template"
        value={value}
        checked={checked}
        disabled={disabled}
        onChange={() => onSelect(value)}
        className="mt-0.5 accent-blue-500"
      />
      <span className="flex-1 min-w-0">
        <div className="font-medium">{label}</div>
        <div className="text-[10px] text-slate-500">{hint}</div>
      </span>
    </label>
  )
}

export default PipelineEditor

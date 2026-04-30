import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../services/api'
import { BlockConfigDto, PipelineConfigDto, ValidationError, ValidationResult } from '../types'

/**
 * Editor state hook. Owns the canonical "current" + "original" PipelineConfig and
 * exposes mutation helpers that always set the dirty flag correctly.
 *
 * <p>Persistence: dirty == JSON-string equality between current and original.
 * Save replaces original with whatever the backend returns from PUT.
 *
 * <p>History: every user-driven mutation pushes a deep-cloned snapshot of the prior
 * state onto a bounded stack (current and history live in one state object so the
 * push and the swap commit atomically — strict-mode double invocation stays safe).
 * Undo pops one entry. The stack is cleared on reload, save, and pipeline switch.
 */
export interface UsePipelineEditor {
  configPath: string | null
  setConfigPath: (path: string | null) => void
  current: PipelineConfigDto | null
  original: PipelineConfigDto | null
  setCurrent: (next: PipelineConfigDto | null) => void
  loading: boolean
  loadError: string | null
  saving: boolean
  saveError: string | null
  validating: boolean
  /** Errors from latest Validate or Save. */
  errors: ValidationError[]
  /** True only when the latest Validate was successful (cleared on edit/save). */
  validatedClean: boolean
  dirty: boolean

  reload: () => Promise<void>
  validate: () => Promise<ValidationResult | null>
  save: () => Promise<boolean>

  /** Undo the most recent mutation. No-op when {@link canUndo} is false. */
  undo: () => void
  /** True iff there is at least one prior state to revert to. */
  canUndo: boolean

  /** Update top-level (name, description, defaults, entry_points, knowledgeBase, triggers). */
  patchConfig: (patch: Partial<PipelineConfigDto>) => void
  /** Replace a single block (by id). */
  patchBlock: (blockId: string, patch: Partial<BlockConfigDto>) => void
  /** Insert a new block (with auto-generated id if needed). */
  addBlock: (block: BlockConfigDto, options?: { afterBlockId?: string }) => void
  /** Remove a block + clean up depends_on / verify / on_failure references. */
  removeBlock: (blockId: string) => void
  /** Add or remove a depends_on edge. */
  setDependsOn: (sourceId: string, targetId: string, present: boolean) => void
  /** Rename a block id and propagate to depends_on / verify / on_failure refs. */
  renameBlock: (oldId: string, newId: string) => boolean
}

function deepClone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v))
}

function eqJson(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

const HISTORY_LIMIT = 50

interface EditorCore {
  current: PipelineConfigDto | null
  history: PipelineConfigDto[]
}

const EMPTY_CORE: EditorCore = { current: null, history: [] }

function pushHistory(history: PipelineConfigDto[], prev: PipelineConfigDto): PipelineConfigDto[] {
  const nh = [...history, deepClone(prev)]
  return nh.length > HISTORY_LIMIT ? nh.slice(nh.length - HISTORY_LIMIT) : nh
}

export function usePipelineEditor(): UsePipelineEditor {
  const [configPath, setConfigPathRaw] = useState<string | null>(null)
  const [core, setCore] = useState<EditorCore>(EMPTY_CORE)
  const [original, setOriginal] = useState<PipelineConfigDto | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [validating, setValidating] = useState(false)
  const [errors, setErrors] = useState<ValidationError[]>([])
  const [validatedClean, setValidatedClean] = useState(false)

  const current = core.current
  const canUndo = core.history.length > 0
  const dirty = useMemo(() => !eqJson(current, original), [current, original])

  /** Run an updater against the latest current; if it returns a new ref, push history + swap. */
  const mutate = useCallback((updater: (prev: PipelineConfigDto) => PipelineConfigDto) => {
    setCore(s => {
      if (!s.current) return s
      const next = updater(s.current)
      if (next === s.current) return s
      return { current: next, history: pushHistory(s.history, s.current) }
    })
    setValidatedClean(false)
  }, [])

  const setCurrent = useCallback((next: PipelineConfigDto | null) => {
    setCore(s => {
      if (!s.current || !next) return { current: next, history: s.history }
      return { current: next, history: pushHistory(s.history, s.current) }
    })
    setValidatedClean(false)
  }, [])

  const setConfigPath = useCallback((path: string | null) => {
    setConfigPathRaw(path)
    setCore(s => ({ current: s.current, history: [] }))
  }, [])

  const reload = useCallback(async () => {
    if (!configPath) return
    setLoading(true)
    setLoadError(null)
    try {
      const cfg = await api.getPipelineConfig(configPath)
      setOriginal(deepClone(cfg))
      setCore({ current: cfg, history: [] })
      setErrors([])
      setSaveError(null)
      setValidatedClean(false)
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Ошибка загрузки конфига')
    } finally {
      setLoading(false)
    }
  }, [configPath])

  // Auto-load when configPath changes
  useEffect(() => {
    if (configPath) reload()
  }, [configPath, reload])

  const validate = useCallback(async (): Promise<ValidationResult | null> => {
    if (!configPath) return null
    setValidating(true)
    setSaveError(null)
    try {
      const result = await api.validatePipeline(configPath)
      setErrors(result.errors ?? [])
      setValidatedClean(result.valid)
      return result
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Не удалось выполнить валидацию')
      return null
    } finally {
      setValidating(false)
    }
  }, [configPath])

  const save = useCallback(async (): Promise<boolean> => {
    if (!configPath || !current) return false
    setSaving(true)
    setSaveError(null)
    try {
      const result = await api.savePipelineConfig(configPath, current)
      // Reset original to what the backend just persisted (post-validation, env-expanded).
      const persisted = result.config ?? current
      setOriginal(deepClone(persisted))
      setCore({ current: persisted, history: [] })
      setErrors([])
      setValidatedClean(true)
      return true
    } catch (e) {
      // Backend returns 400 + {error, errors[]} for validation failures
      const errBody = (e as { body?: { errors?: ValidationError[]; error?: string } } | null)?.body
      if (errBody && Array.isArray(errBody.errors) && errBody.errors.length > 0) {
        setErrors(errBody.errors)
        setSaveError(errBody.error ?? 'Конфиг невалиден — исправьте ошибки.')
      } else {
        setSaveError(e instanceof Error ? e.message : 'Не удалось сохранить')
      }
      return false
    } finally {
      setSaving(false)
    }
  }, [configPath, current])

  const undo = useCallback(() => {
    setCore(s => {
      if (s.history.length === 0) return s
      const prev = s.history[s.history.length - 1]
      return { current: prev, history: s.history.slice(0, s.history.length - 1) }
    })
    setValidatedClean(false)
  }, [])

  const patchConfig = useCallback((patch: Partial<PipelineConfigDto>) => {
    mutate(c => ({ ...c, ...patch }))
  }, [mutate])

  const patchBlock = useCallback((blockId: string, patch: Partial<BlockConfigDto>) => {
    mutate(c => {
      if (!c.pipeline) return c
      return {
        ...c,
        pipeline: c.pipeline.map(b => b.id === blockId ? { ...b, ...patch } : b),
      }
    })
  }, [mutate])

  const addBlock = useCallback((block: BlockConfigDto, options?: { afterBlockId?: string }) => {
    mutate(c => {
      const list = c.pipeline ? [...c.pipeline] : []
      let insertAt = list.length
      if (options?.afterBlockId) {
        const idx = list.findIndex(b => b.id === options.afterBlockId)
        if (idx >= 0) insertAt = idx + 1
      }
      // Ensure unique id
      let id = block.id
      const ids = new Set(list.map(b => b.id))
      let n = 1
      while (ids.has(id)) {
        id = `${block.id}_${++n}`
      }
      const dependsOn = options?.afterBlockId
        ? Array.from(new Set([...(block.depends_on ?? []), options.afterBlockId]))
        : (block.depends_on ?? [])
      list.splice(insertAt, 0, { ...block, id, depends_on: dependsOn })
      return { ...c, pipeline: list }
    })
  }, [mutate])

  const removeBlock = useCallback((blockId: string) => {
    mutate(c => {
      if (!c.pipeline) return c
      return {
        ...c,
        pipeline: c.pipeline
          .filter(b => b.id !== blockId)
          .map(b => {
            const stripped: BlockConfigDto = { ...b }
            if (stripped.depends_on?.includes(blockId)) {
              stripped.depends_on = stripped.depends_on.filter(x => x !== blockId)
            }
            // Don't auto-clear verify/on_failure — leaving the dangling target
            // surfaces as a validation error, which is informative.
            return stripped
          }),
        entry_points: (c.entry_points ?? []).filter(ep => ep.from_block !== blockId),
      }
    })
  }, [mutate])

  const setDependsOn = useCallback((sourceId: string, targetId: string, present: boolean) => {
    if (sourceId === targetId) return
    mutate(c => {
      if (!c.pipeline) return c
      let changed = false
      const next = c.pipeline.map(b => {
        if (b.id !== targetId) return b
        const cur = b.depends_on ?? []
        if (present && !cur.includes(sourceId)) {
          changed = true
          return { ...b, depends_on: [...cur, sourceId] }
        }
        if (!present && cur.includes(sourceId)) {
          changed = true
          return { ...b, depends_on: cur.filter(x => x !== sourceId) }
        }
        return b
      })
      return changed ? { ...c, pipeline: next } : c
    })
  }, [mutate])

  const renameBlock = useCallback((oldId: string, newId: string): boolean => {
    if (!newId || oldId === newId) return false
    let success = false
    mutate(c => {
      if (!c.pipeline) return c
      // Refuse if collision
      if (c.pipeline.some(b => b.id === newId)) return c
      success = true
      return {
        ...c,
        pipeline: c.pipeline.map(b => {
          if (b.id === oldId) return { ...b, id: newId }
          return {
            ...b,
            depends_on: b.depends_on?.map(d => d === oldId ? newId : d),
            verify: b.verify ? {
              ...b.verify,
              subject: b.verify.subject === oldId ? newId : b.verify.subject,
              on_fail: b.verify.on_fail ? {
                ...b.verify.on_fail,
                target: b.verify.on_fail.target === oldId ? newId : b.verify.on_fail.target,
              } : b.verify.on_fail,
            } : b.verify,
            on_failure: b.on_failure ? {
              ...b.on_failure,
              target: b.on_failure.target === oldId ? newId : b.on_failure.target,
            } : b.on_failure,
          }
        }),
        entry_points: (c.entry_points ?? []).map(ep => ({
          ...ep,
          from_block: ep.from_block === oldId ? newId : ep.from_block,
        })),
      }
    })
    return success
  }, [mutate])

  return {
    configPath, setConfigPath,
    current, original, setCurrent,
    loading, loadError, saving, saveError,
    validating, errors, validatedClean, dirty,
    reload, validate, save,
    undo, canUndo,
    patchConfig, patchBlock, addBlock, removeBlock,
    setDependsOn, renameBlock,
  }
}

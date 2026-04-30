import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../services/api'
import { BlockConfigDto, PipelineConfigDto, ValidationError, ValidationResult } from '../types'

/**
 * Editor state hook. Owns the canonical "current" + "original" PipelineConfig and
 * exposes mutation helpers that always set the dirty flag correctly.
 *
 * <p>Persistence: dirty == JSON-string equality between current and original.
 * Save replaces original with whatever the backend returns from PUT.
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

export function usePipelineEditor(): UsePipelineEditor {
  const [configPath, setConfigPath] = useState<string | null>(null)
  const [current, setCurrentRaw] = useState<PipelineConfigDto | null>(null)
  const [original, setOriginal] = useState<PipelineConfigDto | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [validating, setValidating] = useState(false)
  const [errors, setErrors] = useState<ValidationError[]>([])
  const [validatedClean, setValidatedClean] = useState(false)

  const dirty = useMemo(() => !eqJson(current, original), [current, original])

  const setCurrent = useCallback((next: PipelineConfigDto | null) => {
    setCurrentRaw(next)
    // Any edit invalidates the "validated clean" sticker.
    setValidatedClean(false)
  }, [])

  const reload = useCallback(async () => {
    if (!configPath) return
    setLoading(true)
    setLoadError(null)
    try {
      const cfg = await api.getPipelineConfig(configPath)
      setOriginal(deepClone(cfg))
      setCurrentRaw(cfg)
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
      setOriginal(deepClone(result.config ?? current))
      setCurrentRaw(result.config ?? current)
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

  const patchConfig = useCallback((patch: Partial<PipelineConfigDto>) => {
    setCurrentRaw(c => {
      if (!c) return c
      return { ...c, ...patch }
    })
    setValidatedClean(false)
  }, [])

  const patchBlock = useCallback((blockId: string, patch: Partial<BlockConfigDto>) => {
    setCurrentRaw(c => {
      if (!c?.pipeline) return c
      return {
        ...c,
        pipeline: c.pipeline.map(b => b.id === blockId ? { ...b, ...patch } : b),
      }
    })
    setValidatedClean(false)
  }, [])

  const addBlock = useCallback((block: BlockConfigDto, options?: { afterBlockId?: string }) => {
    setCurrentRaw(c => {
      if (!c) return c
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
    setValidatedClean(false)
  }, [])

  const removeBlock = useCallback((blockId: string) => {
    setCurrentRaw(c => {
      if (!c?.pipeline) return c
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
    setValidatedClean(false)
  }, [])

  const setDependsOn = useCallback((sourceId: string, targetId: string, present: boolean) => {
    if (sourceId === targetId) return
    setCurrentRaw(c => {
      if (!c?.pipeline) return c
      return {
        ...c,
        pipeline: c.pipeline.map(b => {
          if (b.id !== targetId) return b
          const cur = b.depends_on ?? []
          if (present && !cur.includes(sourceId)) {
            return { ...b, depends_on: [...cur, sourceId] }
          }
          if (!present && cur.includes(sourceId)) {
            return { ...b, depends_on: cur.filter(x => x !== sourceId) }
          }
          return b
        }),
      }
    })
    setValidatedClean(false)
  }, [])

  const renameBlock = useCallback((oldId: string, newId: string): boolean => {
    if (!newId || oldId === newId) return false
    let success = false
    setCurrentRaw(c => {
      if (!c?.pipeline) return c
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
    if (success) setValidatedClean(false)
    return success
  }, [])

  return {
    configPath, setConfigPath,
    current, original, setCurrent,
    loading, loadError, saving, saveError,
    validating, errors, validatedClean, dirty,
    reload, validate, save,
    patchConfig, patchBlock, addBlock, removeBlock,
    setDependsOn, renameBlock,
  }
}

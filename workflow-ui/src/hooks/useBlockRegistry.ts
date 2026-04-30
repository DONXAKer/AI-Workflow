import { useEffect, useState } from 'react'
import { api } from '../services/api'
import { BlockRegistryEntry } from '../types'

/**
 * Loads the catalog of registered block types from the backend. Caches in module scope
 * so navigating between editor instances doesn't refetch.
 */
let cache: BlockRegistryEntry[] | null = null
let inflight: Promise<BlockRegistryEntry[]> | null = null

async function loadOnce(): Promise<BlockRegistryEntry[]> {
  if (cache) return cache
  if (!inflight) {
    inflight = api.getBlockRegistry().then(d => {
      cache = d
      inflight = null
      return d
    }).catch(e => {
      inflight = null
      throw e
    })
  }
  return inflight
}

export interface UseBlockRegistry {
  registry: BlockRegistryEntry[]
  byType: Record<string, BlockRegistryEntry>
  loading: boolean
  error: string | null
}

export function useBlockRegistry(): UseBlockRegistry {
  const [registry, setRegistry] = useState<BlockRegistryEntry[]>(() => cache ?? [])
  const [loading, setLoading] = useState(!cache)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (cache) {
      setRegistry(cache)
      return
    }
    let cancelled = false
    loadOnce()
      .then(r => {
        if (!cancelled) {
          setRegistry(r)
          setLoading(false)
        }
      })
      .catch(e => {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Не удалось загрузить реестр блоков')
          setLoading(false)
        }
      })
    return () => { cancelled = true }
  }, [])

  const byType: Record<string, BlockRegistryEntry> = {}
  for (const entry of registry) byType[entry.type] = entry
  return { registry, byType, loading, error }
}

/** Test/dev hook: clear the module cache so a follow-up call refetches. */
export function _resetBlockRegistryCacheForTests() {
  cache = null
  inflight = null
}

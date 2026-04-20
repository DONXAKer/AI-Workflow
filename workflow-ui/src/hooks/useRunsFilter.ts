import { useSearchParams } from 'react-router-dom'
import { RunStatus } from '../types'

export interface FilterState {
  status: RunStatus[]
  pipelineName: string
  search: string
  from: string
  to: string
  page: number
}

export function useRunsFilter() {
  const [params, setParams] = useSearchParams()

  const filters: FilterState = {
    status: params.getAll('status') as RunStatus[],
    pipelineName: params.get('pipeline') ?? '',
    search: params.get('search') ?? '',
    from: params.get('from') ?? '',
    to: params.get('to') ?? '',
    page: parseInt(params.get('page') ?? '0', 10),
  }

  function setFilter<K extends keyof FilterState>(key: K, value: FilterState[K]) {
    setParams(prev => {
      const next = new URLSearchParams(prev)
      if (key === 'status') {
        next.delete('status')
        ;(value as RunStatus[]).forEach(s => next.append('status', s))
      } else if (key === 'page') {
        if (value === 0) next.delete('page')
        else next.set('page', String(value))
      } else {
        const urlKey = key === 'pipelineName' ? 'pipeline' : (key as string)
        const strVal = String(value)
        if (!strVal) next.delete(urlKey)
        else next.set(urlKey, strVal)
      }
      if (key !== 'page') next.delete('page')
      return next
    })
  }

  function resetFilters() {
    setParams({})
  }

  return { filters, setFilter, resetFilters }
}

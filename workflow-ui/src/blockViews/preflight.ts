import type { BlockViewSpec } from './index'

export const spec: BlockViewSpec = {
  summary: (out) => {
    const status = typeof out.status === 'string' ? out.status : ''
    const cached = out.cached === true
    const fails = Array.isArray(out.baseline_failures) ? (out.baseline_failures as unknown[]).length : 0
    const buildStatus = typeof out.build_status === 'string' ? out.build_status : ''
    const testStatus = typeof out.test_status === 'string' ? out.test_status : ''
    const source = typeof out.preflight_source === 'string' ? out.preflight_source : ''
    const suffix = cached ? ' · из кэша' : ''

    if (status === 'PASSED') return { label: `✓ baseline OK${suffix}`, ok: true }

    if (status === 'WARNING') {
      // Distinguish "no manifests detected" (no commands ran) from "tests actually failed"
      if (source === 'fallback') {
        return { label: `⚠ нет manifests, preflight skipped${suffix}`, warn: true }
      }
      // Build vs test breakdown so the chip says exactly what's red.
      if (buildStatus === 'failed') return { label: `⚠ build failed${suffix}`, warn: true }
      if (testStatus === 'failed') {
        const fqLabel = fails > 0 ? `${fails} failed tests` : 'tests failed (FQNs N/A)'
        return { label: `⚠ ${fqLabel}${suffix}`, warn: true }
      }
      return { label: `⚠ baseline warning${suffix}`, warn: true }
    }

    if (status === 'RED_BLOCKED') {
      if (buildStatus === 'failed') return { label: `✗ build failed${suffix}`, fail: true }
      if (testStatus === 'failed') {
        return { label: fails > 0 ? `✗ ${fails} failed tests${suffix}` : `✗ tests failed${suffix}`, fail: true }
      }
      return { label: `✗ baseline красный${suffix}`, fail: true }
    }

    return { label: status || '—' }
  },
  fields: [
    { key: 'status', label: 'Статус', kind: 'string', emphasis: true },
    { key: 'build_status', label: 'Сборка', kind: 'string' },
    { key: 'test_status', label: 'Тесты', kind: 'string' },
    { key: 'baseline_failures', label: 'Pre-existing failures (FQN)', kind: 'list' },
    { key: 'preflight_source', label: 'Откуда команды', kind: 'string' },
    { key: 'preflight_detected', label: 'Детектировано', kind: 'string' },
    { key: 'commands', label: 'Команды', kind: 'objList' },
    { key: 'cached', label: 'Из кэша', kind: 'bool' },
    { key: 'cache_source_sha', label: 'Cache SHA', kind: 'string' },
    { key: 'duration_ms', label: 'Длительность (мс)', kind: 'number' },
    { key: 'log_excerpt', label: 'Лог (хвост)', kind: 'multiline' },
  ],
}

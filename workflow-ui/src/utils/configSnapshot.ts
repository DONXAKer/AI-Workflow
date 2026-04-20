import { BlockSnapshot, ApprovalMode } from '../types'

/**
 * Парсит {@code PipelineRun.configSnapshotJson} в карту blockId → snapshot.
 * Snapshot-фиксация backend'а содержит весь PipelineConfig, включая
 * {@code pipeline: BlockConfig[]}. Возвращает пустую карту при ошибке парсинга.
 */
export function parseConfigSnapshot(json: string | null | undefined): Map<string, BlockSnapshot> {
  const map = new Map<string, BlockSnapshot>()
  if (!json) return map
  try {
    const parsed = JSON.parse(json) as { pipeline?: unknown }
    const pipeline = parsed.pipeline
    if (!Array.isArray(pipeline)) return map
    for (const raw of pipeline) {
      if (!raw || typeof raw !== 'object') continue
      const r = raw as Record<string, unknown>
      const id = typeof r.id === 'string' ? r.id : undefined
      if (!id) continue
      const snapshot: BlockSnapshot = {
        id,
        block: typeof r.block === 'string' ? r.block : '',
        approval_mode: normalizeApprovalMode(r.approval_mode ?? r.approvalMode),
        approval: typeof r.approval === 'boolean' ? r.approval : undefined,
        enabled: typeof r.enabled === 'boolean' ? r.enabled : undefined,
        timeout: toNumber(r.timeout ?? r.timeoutSeconds),
        condition: typeof r.condition === 'string' ? r.condition : undefined,
      }
      map.set(id, snapshot)
    }
  } catch {
    // bad JSON — return empty map, UI just falls back to no extra info
  }
  return map
}

/** Canonical approval mode — falls back to `manual`/`auto` based on legacy boolean flag. */
export function effectiveApprovalMode(snapshot: BlockSnapshot | undefined): ApprovalMode | undefined {
  if (!snapshot) return undefined
  if (snapshot.approval_mode) return snapshot.approval_mode
  if (snapshot.approval === true) return 'manual'
  if (snapshot.approval === false) return 'auto'
  return undefined
}

function normalizeApprovalMode(v: unknown): ApprovalMode | undefined {
  if (v === 'manual' || v === 'auto' || v === 'auto_notify') return v
  return undefined
}

function toNumber(v: unknown): number | undefined {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string' && v.trim().length > 0) {
    const n = Number(v)
    return Number.isFinite(n) ? n : undefined
  }
  return undefined
}

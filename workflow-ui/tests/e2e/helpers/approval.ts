import type { Page } from '@playwright/test'
import { apiGet, apiPost } from './api-client'

const TERMINAL = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const POLL_INTERVAL_MS = 5_000

export interface AutoApproveOpts {
  projectSlug: string
  maxMinutes?: number
  /** Called once per distinct approval gate, before the helper auto-approves. */
  onApprovalGate?: (blockId: string) => Promise<void> | void
  /** Called every poll iteration (good place for incremental screenshots). */
  onPoll?: (status: string, currentBlock: string | null) => Promise<void> | void
}

interface RunStateProbe {
  status: string
  currentBlock?: string | null
}

/**
 * Polls GET /api/runs/{id} every 5s. On PAUSED_FOR_APPROVAL, fires the
 * onApprovalGate callback (screenshot opportunity), then POSTs APPROVE for the
 * paused block. Returns the terminal status, or throws on timeout.
 *
 * Why polling vs STOMP/WS: simpler — no SockJS client in the test, no race with
 * reconnects, latency 5s vs minutes-scale blocks is fine.
 */
export async function autoApproveUntilTerminal(
  page: Page,
  runId: string,
  opts: AutoApproveOpts,
): Promise<string> {
  const maxMs = (opts.maxMinutes ?? 45) * 60_000
  const deadline = Date.now() + maxMs
  const approved = new Set<string>()
  let lastStatus = ''
  while (Date.now() < deadline) {
    let probe: RunStateProbe
    try {
      probe = await apiGet<RunStateProbe>(page, `/api/runs/${runId}`, {
        projectSlug: opts.projectSlug,
      })
    } catch {
      // Transient backend hiccup — retry next tick rather than fail the whole run.
      await page.waitForTimeout(POLL_INTERVAL_MS)
      continue
    }
    const status = probe.status ?? ''
    const currentBlock = probe.currentBlock ?? null
    lastStatus = status
    if (opts.onPoll) await opts.onPoll(status, currentBlock)
    if (TERMINAL.has(status)) return status
    if (status === 'PAUSED_FOR_APPROVAL' && currentBlock && !approved.has(currentBlock)) {
      approved.add(currentBlock)
      if (opts.onApprovalGate) await opts.onApprovalGate(currentBlock)
      await apiPost(
        page,
        `/api/runs/${runId}/approval`,
        { blockId: currentBlock, decision: 'APPROVE' },
        { projectSlug: opts.projectSlug },
      )
    }
    await page.waitForTimeout(POLL_INTERVAL_MS)
  }
  throw new Error(
    `autoApproveUntilTerminal: timed out after ${opts.maxMinutes ?? 45} min — last status ${lastStatus}`,
  )
}

import type { Page } from '@playwright/test'
import path from 'node:path'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..', '..', '..', 'test-results', 'e2e-flows')

function safe(part: string): string {
  return part.replace(/[^a-zA-Z0-9._-]/g, '_')
}

/**
 * Writes a deterministic-named screenshot to
 * `test-results/e2e-flows/{pipeline}/{ep}/{NN}-{step}.png`.
 *
 * Naming is sortable so the HTML report and the on-disk gallery render the
 * pipeline timeline in the right order.
 */
export async function shot(
  page: Page,
  pipeline: string,
  ep: string,
  n: number,
  step: string,
): Promise<string> {
  const dir = path.join(ROOT, safe(pipeline), safe(ep))
  fs.mkdirSync(dir, { recursive: true })
  const file = path.join(dir, `${String(n).padStart(2, '0')}-${safe(step)}.png`)
  await page.screenshot({ path: file, fullPage: true })
  return file
}

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { loadEnv } from './env'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FIXTURE_DIR = path.resolve(HERE, '..', 'fixtures', 'mini-target-repo')

/**
 * Root for fresh target-repo copies. Defaults to OS temp dir — fine for
 * non-Docker runs where backend and test share filesystem. When the backend
 * runs in a different container, set `E2E_TARGET_REPO_ROOT` to a path that
 * is mounted at the SAME absolute path inside both containers (e.g. mount
 * `D:/wf-e2e-tmp` to `/projects/wf-e2e-tmp` in test container; backend already
 * sees `D:/wf-e2e-tmp` as `/projects/wf-e2e-tmp` via its PROJECTS_ROOT mount).
 */
function repoRoot(): string {
  return process.env.E2E_TARGET_REPO_ROOT ?? path.join(os.tmpdir(), 'wf-e2e')
}

/**
 * Copies fixtures/mini-target-repo into a fresh temp folder and `git init`s it,
 * returning the absolute path. Each spec invocation gets a clean tree so that
 * codegen/git_commit blocks operate from a known baseline.
 *
 * The folder is kept after the test by default (debug-friendly) — set E2E_KEEP=0
 * to wipe on success.
 */
export async function prepareTargetRepo(specSlug: string): Promise<string> {
  loadEnv()
  const safeSlug = specSlug.replace(/[^a-zA-Z0-9._-]/g, '_')
  const base = path.join(repoRoot(), `${safeSlug}-${randomUUID().slice(0, 8)}`)
  fs.rmSync(base, { recursive: true, force: true })
  fs.mkdirSync(base, { recursive: true })
  copyDirSync(FIXTURE_DIR, base)
  initGitRepo(base)
  return base
}

function copyDirSync(src: string, dst: string): void {
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, entry.name)
    const d = path.join(dst, entry.name)
    if (entry.isDirectory()) {
      fs.mkdirSync(d, { recursive: true })
      copyDirSync(s, d)
    } else if (entry.isFile()) {
      fs.copyFileSync(s, d)
    }
  }
}

function initGitRepo(repoDir: string): void {
  const git = (args: string[]) =>
    execFileSync('git', args, {
      cwd: repoDir,
      stdio: ['ignore', 'ignore', 'pipe'],
      env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
    })
  git(['init', '--quiet', '--initial-branch=main'])
  git(['config', 'user.email', 'e2e@local'])
  git(['config', 'user.name', 'wf-e2e'])
  git(['add', '.'])
  git(['commit', '--quiet', '-m', 'chore: initial fixture state'])
}

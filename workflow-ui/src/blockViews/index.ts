import type { ReactNode } from 'react'

export interface FieldSpec {
  key: string
  label: string
  kind: 'string' | 'multiline' | 'list' | 'objList' | 'bool' | 'number'
  emphasis?: boolean
}

export interface SummaryResult {
  label: string
  ok?: boolean
  warn?: boolean
  fail?: boolean
}

export interface BlockViewSpec {
  /** Override for the main-row summary chip */
  summary?: (out: Record<string, unknown>) => SummaryResult
  /** Ordered fields to render in Output section (replaces KNOWN_FIELDS for this block) */
  fields?: FieldSpec[]
  /** Fields to render in Input section */
  inputFields?: FieldSpec[]
  /** Custom renderer — used instead of fields[] when present */
  renderOutput?: (out: Record<string, unknown>) => ReactNode
}

import { spec as verifyAcceptanceSpec } from './verify_acceptance'
import { spec as reviewImplSpec } from './review_impl'
import { spec as aiReviewSpec } from './ai_review'
import { spec as implServerSpec } from './impl_server'
import { spec as codeGenerationSpec } from './code_generation'
import { spec as taskMdSpec } from './task_md'
import { spec as analysisSpec } from './analysis'
import { spec as shellExecSpec } from './shell_exec'
import { spec as verifyBlockSpec } from './verify_block'
import { spec as planBlockSpec } from './plan_block'
import { spec as clarificationSpec } from './clarification'
import { spec as preflightSpec } from './preflight'
import { spec as intakeAssessmentSpec } from './intake_assessment'
import { spec as contextScanSpec } from './context_scan'
import { spec as testPlanningSpec } from './test_planning'
import { spec as runReportSpec } from './run_report'

const REGISTRY: Record<string, BlockViewSpec> = {
  // Task & analysis
  task_md: taskMdSpec,
  analysis: analysisSpec,
  clarification: clarificationSpec,

  // SDLC quality sensors
  preflight: preflightSpec,
  intake_assessment: intakeAssessmentSpec,
  context_scan: contextScanSpec,
  test_planning: testPlanningSpec,
  run_report: runReportSpec,

  // Shell exec blocks
  branch_setup: shellExecSpec,
  create_branch: shellExecSpec,
  build: shellExecSpec,
  tests: shellExecSpec,
  validate_contracts: shellExecSpec,
  ai_simulator: shellExecSpec,
  check_unreal: shellExecSpec,
  diff_review: shellExecSpec,
  commit: shellExecSpec,
  git_push: shellExecSpec,
  pr_link: shellExecSpec,
  implement: shellExecSpec,

  // Verify blocks
  verify_build: verifyBlockSpec,
  verify_tests: verifyBlockSpec,
  verify_contracts: verifyBlockSpec,
  verify_ai_simulator: verifyBlockSpec,

  // Orchestrator plan blocks
  plan_impl: planBlockSpec,
  plan_bp: planBlockSpec,

  // Orchestrator review blocks
  review_impl: reviewImplSpec,
  review_bp: reviewImplSpec,

  // Agent impl blocks
  impl_server: implServerSpec,
  impl_bp: implServerSpec,

  // Agent verify
  verify_acceptance: verifyAcceptanceSpec,

  // AI code review
  ai_review: aiReviewSpec,
}

/**
 * Fallback registry keyed by block TYPE — used when no spec is registered for
 * the exact YAML block id. Lets ad-hoc pipelines (bugfix/docs/feature-generic
 * with their own block ids like `impl`, `impl_docs`, `verify_fix`, `verify_docs`)
 * still get a structured output view instead of a raw JSON dump.
 */
const TYPE_REGISTRY: Record<string, BlockViewSpec> = {
  task_md_input: taskMdSpec,
  analysis: analysisSpec,
  clarification: clarificationSpec,
  preflight: preflightSpec,
  intake_assessment: intakeAssessmentSpec,
  context_scan: contextScanSpec,
  test_planning: testPlanningSpec,
  run_report: runReportSpec,
  shell_exec: shellExecSpec,
  verify: verifyBlockSpec,
  agent_with_tools: implServerSpec,
  agent_verify: verifyAcceptanceSpec,
  code_generation: codeGenerationSpec,
  ai_review: aiReviewSpec,
  orchestrator: {
    summary: (out) => {
      if (typeof out.goal === 'string' && out.goal) return planBlockSpec.summary!(out)
      return reviewImplSpec.summary!(out)
    },
    // Plan mode: renderOutput returns null → BlockProgressTable falls back to StructuredOutput
    // with fields from this spec. Review mode: use reviewImplSpec custom renderer.
    fields: planBlockSpec.fields,
    renderOutput: (out) =>
      typeof out.goal === 'string' && out.goal
        ? null
        : reviewImplSpec.renderOutput!(out),
  },
}

export function getBlockView(blockId: string, blockType?: string): BlockViewSpec | undefined {
  return REGISTRY[blockId] ?? (blockType ? TYPE_REGISTRY[blockType] : undefined)
}

/**
 * Editor-local types. Distinct from the wire types in src/types.ts:
 * the React-Flow nodes/edges carry just enough data to render — the canonical
 * config lives in PipelineConfigDto (the JSON we sent / will send to the backend).
 */

import type { Node, Edge } from '@xyflow/react'
import { BlockConfigDto, ValidationError } from '../../types'

export interface BlockNodeData extends Record<string, unknown> {
  /** The block id (== the YAML id field, also the ReactFlow node id). */
  blockId: string
  /** The block type (e.g. agent_with_tools). */
  blockType: string
  /** Human label rendered as the node's title. */
  label: string
  /** Russian display name (used as a subtitle when present). */
  displayName?: string
  /** Set true to highlight this node (selected). */
  selected: boolean
  /** Per-node validation errors. Empty = no badge. */
  errors: ValidationError[]
  /** True when the block sets a `condition:`. */
  hasCondition: boolean
  /** Set when this block id is referenced as `from_block` of an entry point. */
  entryPointId?: string
  /** When set, the block is disabled (`enabled: false`) — render with strike-through. */
  disabled: boolean
}

export type BlockNode = Node<BlockNodeData, 'block'>

export type EdgeKind = 'depends_on' | 'verify_loopback' | 'on_failure_loopback'

export interface PipelineEdgeData extends Record<string, unknown> {
  kind: EdgeKind
  /** True if the source/target block ID doesn't match any block (broken ref). */
  broken?: boolean
}

export type PipelineEdge = Edge<PipelineEdgeData>

/** Block category → palette grouping. */
export type BlockCategory = 'input' | 'agent' | 'verify' | 'ci' | 'infra' | 'output' | 'general'

export interface SelectionState {
  kind: 'none' | 'block'
  blockId?: string
}

export interface EditorState {
  configPath: string | null
  /** Current edited config (the live JSON the editor mutates). */
  current: import('../../types').PipelineConfigDto | null
  /** Snapshot taken at load time — used for dirty detection. */
  original: import('../../types').PipelineConfigDto | null
  selection: SelectionState
  validationErrors: ValidationError[]
  /** True when dirty (current != original by JSON equality). */
  dirty: boolean
}

/** Convenience: extract a block by id. */
export function findBlock(cfg: import('../../types').PipelineConfigDto | null, id: string): BlockConfigDto | undefined {
  return cfg?.pipeline?.find(b => b.id === id)
}

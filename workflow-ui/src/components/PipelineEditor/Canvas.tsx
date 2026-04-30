import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Background,
  Connection,
  ConnectionLineType,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
} from '@xyflow/react'
import type { Edge as RFEdge } from '@xyflow/react'
import '@xyflow/react/dist/style.css'

import { PipelineConfigDto, ValidationError } from '../../types'
import { autoLayout } from './layout'
import BlockNode from './BlockNode'
import { BlockNode as BlockNodeT, BlockNodeData, PipelineEdge, PipelineEdgeData } from './types'
import { blockIdLabel } from '../../utils/blockLabels'

interface CanvasProps {
  config: PipelineConfigDto
  selectedBlockId: string | null
  errors: ValidationError[]
  onSelectBlock: (id: string | null) => void
  onConnectDependsOn: (sourceId: string, targetId: string) => void
  onDeleteEdge: (sourceId: string, targetId: string) => void
}

const nodeTypes = { block: BlockNode }

export function Canvas(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <CanvasInner {...props} />
    </ReactFlowProvider>
  )
}

function CanvasInner({
  config, selectedBlockId, errors, onSelectBlock, onConnectDependsOn, onDeleteEdge,
}: CanvasProps) {
  const [hoveredEdge, setHoveredEdge] = useState<string | null>(null)

  // Build initial node + edge arrays from the config
  const { nodes: builtNodes, edges: builtEdges } = useMemo(
    () => buildGraph(config, selectedBlockId, errors),
    [config, selectedBlockId, errors],
  )

  const laidOutNodes = useMemo(() => autoLayout(builtNodes, builtEdges, 'TB'), [builtNodes, builtEdges])

  const [nodes, setNodes, onNodesChange] = useNodesState(laidOutNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(builtEdges)

  // Re-sync when the config changes externally
  useEffect(() => {
    setNodes(laidOutNodes)
    setEdges(builtEdges)
  }, [laidOutNodes, builtEdges, setNodes, setEdges])

  const onConnect = useCallback((c: Connection) => {
    if (c.source && c.target && c.source !== c.target) {
      onConnectDependsOn(c.source, c.target)
    }
  }, [onConnectDependsOn])

  const onNodeClick = useCallback((_e: React.MouseEvent, node: BlockNodeT) => {
    onSelectBlock(node.id)
  }, [onSelectBlock])

  const onPaneClick = useCallback(() => {
    onSelectBlock(null)
  }, [onSelectBlock])

  const onEdgeClick = useCallback((_e: React.MouseEvent, edge: RFEdge<PipelineEdgeData>) => {
    if (edge.data?.kind === 'depends_on') {
      onDeleteEdge(edge.source, edge.target)
    }
  }, [onDeleteEdge])

  return (
    <div data-testid="pipeline-canvas" className="w-full h-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        onEdgeClick={onEdgeClick}
        onEdgeMouseEnter={(_e, edge) => setHoveredEdge(edge.id)}
        onEdgeMouseLeave={() => setHoveredEdge(null)}
        nodeTypes={nodeTypes}
        connectionLineType={ConnectionLineType.SmoothStep}
        fitView
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={16} color="#1e293b" />
        <MiniMap pannable zoomable nodeColor="#64748b" />
        <Controls />
      </ReactFlow>
      {/* Visually-hidden text used by screen readers and tests to detect hovered edge */}
      {hoveredEdge && (
        <span data-testid="hovered-edge" className="sr-only">{hoveredEdge}</span>
      )}
    </div>
  )
}

function buildGraph(
  config: PipelineConfigDto,
  selectedBlockId: string | null,
  errors: ValidationError[],
): { nodes: BlockNodeT[]; edges: PipelineEdge[] } {
  const blocks = config.pipeline ?? []
  const blockIdSet = new Set(blocks.map(b => b.id))

  // Compute "from_block" → entry point id for badges
  const entryByBlock: Record<string, string> = {}
  for (const ep of config.entry_points ?? []) {
    if (ep.from_block && ep.id) entryByBlock[ep.from_block] = ep.id
  }

  // Group errors by blockId for per-node tooltip
  const errsByBlock: Record<string, ValidationError[]> = {}
  for (const e of errors ?? []) {
    if (!e.blockId) continue
    ;(errsByBlock[e.blockId] ??= []).push(e)
  }

  const nodes: BlockNodeT[] = blocks.map((b, idx) => {
    const data: BlockNodeData = {
      blockId: b.id,
      blockType: b.block,
      label: blockIdLabel(b.id),
      displayName: blockIdLabel(b.id),
      selected: b.id === selectedBlockId,
      errors: errsByBlock[b.id] ?? [],
      hasCondition: !!b.condition && b.condition.trim().length > 0,
      entryPointId: entryByBlock[b.id],
      disabled: b.enabled === false,
    }
    return {
      id: b.id,
      type: 'block',
      data,
      // initial fallback positions; autoLayout overwrites
      position: { x: 0, y: idx * 110 },
    }
  })

  const edges: PipelineEdge[] = []

  // depends_on edges
  for (const b of blocks) {
    for (const dep of b.depends_on ?? []) {
      edges.push({
        id: `dep:${dep}->${b.id}`,
        source: dep,
        target: b.id,
        type: 'smoothstep',
        data: { kind: 'depends_on', broken: !blockIdSet.has(dep) },
        style: blockIdSet.has(dep) ? undefined : { stroke: '#ef4444' },
      })
    }
    // verify.on_fail.target — dashed orange loopback
    const vfTarget = b.verify?.on_fail?.target
    if (vfTarget && b.verify?.on_fail?.action === 'loopback') {
      const maxIter = b.verify?.on_fail?.max_iterations
      edges.push({
        id: `vf:${b.id}->${vfTarget}`,
        source: b.id,
        target: vfTarget,
        type: 'smoothstep',
        animated: true,
        label: maxIter ? `verify не прошёл · до ${maxIter} итер.` : 'verify не прошёл',
        labelStyle: { fill: '#fb923c', fontSize: 10 },
        style: { stroke: '#fb923c', strokeDasharray: '6 4' },
        data: { kind: 'verify_loopback', broken: !blockIdSet.has(vfTarget) },
      })
    }
    // on_failure.target — dashed red loopback
    const ofTarget = b.on_failure?.target
    if (ofTarget && b.on_failure?.action === 'loopback') {
      const maxIter = b.on_failure?.max_iterations
      edges.push({
        id: `of:${b.id}->${ofTarget}`,
        source: b.id,
        target: ofTarget,
        type: 'smoothstep',
        animated: true,
        label: maxIter ? `при ошибке · до ${maxIter} итер.` : 'при ошибке',
        labelStyle: { fill: '#f87171', fontSize: 10 },
        style: { stroke: '#f87171', strokeDasharray: '6 4' },
        data: { kind: 'on_failure_loopback', broken: !blockIdSet.has(ofTarget) },
      })
    }
  }

  return { nodes, edges }
}

export default Canvas

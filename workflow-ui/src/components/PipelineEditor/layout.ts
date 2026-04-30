import dagre from 'dagre'
import { BlockNode, PipelineEdge } from './types'

/**
 * Auto-layout: feeds the current nodes/edges into dagre and returns a fresh node array
 * with x/y positions overwritten. We always recompute on changes — there is no
 * persisted layout (per spec).
 *
 * <p>Direction defaults to TB (top-to-bottom). The graph treats only `depends_on` edges
 * as DAG forward-edges; loopback edges are added back AFTER layout so they don't
 * confuse dagre into stretching the chart.
 */
const NODE_WIDTH = 220
const NODE_HEIGHT = 90

export function autoLayout(
  nodes: BlockNode[],
  edges: PipelineEdge[],
  direction: 'TB' | 'LR' = 'TB',
): BlockNode[] {
  if (nodes.length === 0) return nodes

  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: direction, nodesep: 60, ranksep: 80, marginx: 24, marginy: 24 })

  for (const n of nodes) {
    g.setNode(n.id, { width: NODE_WIDTH, height: NODE_HEIGHT })
  }
  // Only depends_on shapes the rank order — loopbacks are decorative.
  for (const e of edges) {
    if (e.data?.kind !== 'depends_on') continue
    if (!nodes.some(n => n.id === e.source) || !nodes.some(n => n.id === e.target)) continue
    g.setEdge(e.source, e.target)
  }

  dagre.layout(g)

  return nodes.map(n => {
    const pos = g.node(n.id)
    if (!pos) return n
    // dagre returns the node's CENTER — react-flow expects top-left.
    return {
      ...n,
      position: { x: pos.x - NODE_WIDTH / 2, y: pos.y - NODE_HEIGHT / 2 },
    }
  })
}

export const NODE_DIMENSIONS = { width: NODE_WIDTH, height: NODE_HEIGHT }

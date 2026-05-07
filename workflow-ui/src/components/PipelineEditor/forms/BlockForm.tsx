import { BlockConfigDto, BlockRegistryEntry, PipelineConfigDto, VerifyConfigDto } from '../../../types'
import GenericBlockForm, { LevelFilter } from './GenericBlockForm'
import AgentWithToolsForm from './AgentWithToolsForm'
import VerifyForm from './VerifyForm'

/**
 * Pure form-dispatcher: picks the right per-block-type editor (custom form for
 * `agent_with_tools` / `verify`, generic schema-driven form otherwise) and
 * filters fields by UI level.
 *
 * Extracted from {@code SidePanel.tsx} (PR-3, 2026-05-07) so the Creation
 * Wizard can re-use it with {@code levelFilter="essential"} for the
 * BlockSlotEditor.
 */
export interface BlockFormProps {
  block: BlockConfigDto
  registryEntry: BlockRegistryEntry | undefined
  config: PipelineConfigDto
  levelFilter: LevelFilter
  onConfigChange: (cfg: Record<string, unknown>) => void
  onVerifyChange: (v: VerifyConfigDto) => void
}

export function BlockForm({
  block, registryEntry, config, levelFilter, onConfigChange, onVerifyChange,
}: BlockFormProps) {
  // Custom forms first
  if (block.block === 'agent_with_tools') {
    return (
      <AgentWithToolsForm
        block={block}
        config={config}
        onChange={onConfigChange}
        levelFilter={levelFilter}
      />
    )
  }
  if (block.block === 'verify') {
    // VerifyForm renders subject / checks / llm_check (Essentials).
    // It deliberately does NOT render on_fail — that lives in
    // ConditionsAndRetrySection in the side panel.
    if (levelFilter === 'advanced') return null
    return <VerifyForm block={block} config={config} onChange={onVerifyChange} />
  }
  // Generic from FieldSchema
  if (registryEntry?.metadata?.configFields?.length) {
    return (
      <GenericBlockForm
        block={block}
        fields={registryEntry.metadata.configFields}
        config={config}
        onChange={onConfigChange}
        levelFilter={levelFilter}
      />
    )
  }
  // No metadata at all + Essentials section → nothing to show; Advanced gets RawJsonFallback.
  return null
}

export default BlockForm

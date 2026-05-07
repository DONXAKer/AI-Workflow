import { BlockConfigDto } from '../../../types'
import GenericBlockForm, { LevelFilter } from './GenericBlockForm'
import { FieldSchemaDto, PipelineConfigDto } from '../../../types'

/**
 * Local field schema — kept as a safety net so the form works even if the
 * backend's `agent_with_tools` metadata fails to load. `level` annotations
 * mirror PR-1's backend tagging.
 */
const FIELDS: FieldSchemaDto[] = [
  {
    name: 'user_message', label: 'User message', type: 'string', required: true,
    description: 'Сообщение для модели. Поддерживает ${block.field} и {placeholder}.',
    hints: { multiline: true, monospace: true },
    level: 'essential',
  },
  {
    name: 'working_dir', label: 'Рабочая директория', type: 'string',
    description: 'Абсолютный путь; если пусто — workingDir проекта.',
    level: 'essential',
  },
  {
    name: 'allowed_tools', label: 'Разрешённые инструменты', type: 'tool_list',
    description: 'Подмножество [Read, Write, Edit, Glob, Grep, Bash].',
    level: 'essential',
  },
  {
    name: 'bash_allowlist', label: 'Bash allowlist', type: 'string_array',
    description: 'Шаблоны вида Bash(git *), Bash(gradle *). Пустой — Bash отключён.',
    level: 'advanced',
  },
  {
    name: 'max_iterations', label: 'Max iterations', type: 'number',
    defaultValue: 40, description: 'Максимум раундов агента.',
    level: 'advanced',
  },
  {
    name: 'budget_usd_cap', label: 'Бюджет USD', type: 'number',
    defaultValue: 5, description: 'Лимит стоимости вызовов LLM.',
    level: 'advanced',
  },
  {
    name: 'preload_from', label: 'Preload from', type: 'string',
    description: 'ID блока, из output которого загружается контекст.',
    level: 'advanced',
  },
]

interface Props {
  block: BlockConfigDto
  config: PipelineConfigDto
  onChange: (config: Record<string, unknown>) => void
  /** Section-aware filter (default `'all'` for backwards-compat). */
  levelFilter?: LevelFilter
}

/**
 * Custom form for {@code agent_with_tools}. Wraps GenericBlockForm but pins the
 * field set so we never depend on registry metadata being fully populated for this
 * critical block type.
 */
export function AgentWithToolsForm({ block, config, onChange, levelFilter = 'all' }: Props) {
  return (
    <GenericBlockForm
      block={block}
      fields={FIELDS}
      config={config}
      onChange={onChange}
      levelFilter={levelFilter}
    />
  )
}

export default AgentWithToolsForm

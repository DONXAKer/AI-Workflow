import { BlockConfigDto } from '../../../types'
import GenericBlockForm from './GenericBlockForm'
import { FieldSchemaDto, PipelineConfigDto } from '../../../types'

const FIELDS: FieldSchemaDto[] = [
  {
    name: 'user_message', label: 'User message', type: 'string', required: true,
    description: 'Сообщение для модели. Поддерживает ${block.field} и {placeholder}.',
    hints: { multiline: true, monospace: true },
  },
  {
    name: 'working_dir', label: 'Рабочая директория', type: 'string',
    description: 'Абсолютный путь; если пусто — workingDir проекта.',
  },
  {
    name: 'allowed_tools', label: 'Разрешённые инструменты', type: 'tool_list',
    description: 'Подмножество [Read, Write, Edit, Glob, Grep, Bash].',
  },
  {
    name: 'bash_allowlist', label: 'Bash allowlist', type: 'string_array',
    description: 'Шаблоны вида Bash(git *), Bash(gradle *). Пустой — Bash отключён.',
  },
  {
    name: 'max_iterations', label: 'Max iterations', type: 'number',
    defaultValue: 40, description: 'Максимум раундов агента.',
  },
  {
    name: 'budget_usd_cap', label: 'Бюджет USD', type: 'number',
    defaultValue: 5, description: 'Лимит стоимости вызовов LLM.',
  },
]

interface Props {
  block: BlockConfigDto
  config: PipelineConfigDto
  onChange: (config: Record<string, unknown>) => void
}

/**
 * Custom form for {@code agent_with_tools}. Wraps GenericBlockForm but pins the
 * field set so we never depend on registry metadata being fully populated for this
 * critical block type.
 */
export function AgentWithToolsForm({ block, config, onChange }: Props) {
  return (
    <GenericBlockForm
      block={block}
      fields={FIELDS}
      config={config}
      onChange={onChange}
    />
  )
}

export default AgentWithToolsForm

/** Russian display names for pipeline block types. */
export const BLOCK_TYPE_LABELS: Record<string, string> = {
  // Core pipeline
  analysis:            'Анализ требований',
  clarification:       'Уточнение требований',
  business_intake:     'Ввод задачи',
  task_input:          'Ввод задачи',
  code_generation:     'Генерация кода',
  verify:              'Верификация',
  ai_review:           'AI-ревью кода',

  // Tests & Build
  test_generation:     'Генерация тестов',
  run_tests:           'Запуск тестов',
  build:               'Сборка',

  // Deploy & Release
  deploy:              'Деплой',
  rollback:            'Откат',
  vcs_merge:           'Слияние веток',
  verify_prod:         'Проверка прода',
  release_notes:       'Заметки о релизе',

  // VCS integrations
  git_branch_input:    'Ветка Git',
  gitlab_mr:           'Создание MR',
  gitlab_ci:           'CI/CD GitLab',
  github_pr:           'Pull Request',
  github_actions:      'GitHub Actions',
  mr_input:            'Существующий MR/PR',

  // YouTrack
  youtrack_input:      'Задача YouTrack',
  youtrack_tasks_input:'Задачи YouTrack',
  youtrack_tasks:      'Подзадачи YouTrack',

  // Phase 1 / agentic
  agent_with_tools:    'Агент с инструментами',
  agent_verify:        'Агент-верификатор',
  orchestrator:        'Оркестратор',
  task_md_input:       'Чтение задачи',
  shell_exec:          'Команда Shell',
  claude_code_shell:   'Claude Code',
  http_get:            'HTTP-запрос',

  // SDLC quality (preflight + intake_assessment + context_scan + test_planning + run_report)
  preflight:           'Предполёт (build/test baseline)',
  intake_assessment:   'Оценка ясности требования',
  context_scan:        'Скан tech-стека и conventions',
  test_planning:       'План тестов (стратегия + BVA)',
  run_report:          'Финальный отчёт по run-у',
}

/** Returns Russian label for a block type, or the raw type if unknown. */
export function blockTypeLabel(blockType: string | undefined): string | undefined {
  if (!blockType) return undefined
  return BLOCK_TYPE_LABELS[blockType]
}

/**
 * Well-known block IDs → Russian names.
 * Covers IDs that differ from their block type (e.g. plan → Оркестратор was too generic).
 */
const BLOCK_ID_LABELS: Record<string, string> = {
  // skill-marketplace pipeline
  youtrack_input:   'Задача YouTrack',
  task_md:          'Чтение задачи',
  analysis:         'Анализ требований',
  clarification:    'Уточнение требований',
  plan:             'Планирование',
  codegen:          'Генерация кода',
  build_test:       'Сборка и тесты',
  review:           'Ревью кода',
  pr:               'Pull Request',
  ci:               'CI/CD',

  // WarCard / feature pipeline
  create_branch:    'Создание ветки',
  setup_branch:     'Создание ветки',
  plan_impl:        'Планирование',
  impl_server:      'Реализация (сервер)',
  impl:             'Реализация',
  review_impl:      'Ревью реализации',
  tests:            'Запуск тестов',
  validate_contracts:'Проверка контрактов',
  verify_contracts: 'Верификация контрактов',
  ai_simulator:     'AI-симулятор (smoke)',
  verify_ai_simulator: 'Проверка AI-симулятора',
  check_unreal:     'Проверка Unreal',
  plan_bp:          'Планирование Blueprint',
  impl_bp:          'Реализация Blueprint',
  review_bp:        'Ревью Blueprint',
  plan_docs:        'Планирование документации',
  impl_docs:        'Правка документации',
  review_docs:      'Ревью документации',
  diff_review:      'Просмотр изменений',
  commit:           'Коммит',
  git_push:         'Push в remote',
  pr_link:          'Ссылка на PR',
  verify_build:     'Проверка сборки',
  verify_tests:     'Проверка тестов',

  // generic aliases
  code_generation:  'Генерация кода',
  verify_code:      'Верификация кода',
  verify_analysis:  'Верификация анализа',
  deploy:           'Деплой',
  rollback:         'Откат',
  release:          'Релиз',

  // SDLC quality (block IDs match block types here, but explicit for clarity)
  preflight:           'Предполёт',
  intake_assessment:   'Оценка ясности',
  context_scan:        'Скан стека',
  test_planning:       'План тестов',
  run_report:          'Отчёт по run-у',
  verify_acceptance:   'Acceptance-верификация',
}

/**
 * Returns Russian display label for a block ID.
 * Tries ID-specific map first, then falls back to type-based map (many IDs match type names).
 */
export function blockIdLabel(blockId: string | undefined | null): string {
  if (!blockId) return '—'
  return BLOCK_ID_LABELS[blockId] ?? BLOCK_TYPE_LABELS[blockId] ?? blockId
}

/**
 * Returns "Russian (english)" if a Russian label exists for the block ID, otherwise the raw ID.
 * Use in headings where both a friendly name and the canonical identifier matter.
 */
export function blockIdLabelWithCode(blockId: string | undefined | null): string {
  if (!blockId) return '—'
  const ru = BLOCK_ID_LABELS[blockId] ?? BLOCK_TYPE_LABELS[blockId]
  return ru && ru !== blockId ? `${ru} (${blockId})` : blockId
}

/**
 * Returns "Russian (english)" if a Russian label exists for the block type, otherwise the raw type.
 * Use in palettes and pickers that list block types.
 */
export function blockTypeLabelWithCode(blockType: string | undefined): string {
  if (!blockType) return ''
  const ru = BLOCK_TYPE_LABELS[blockType]
  return ru && ru !== blockType ? `${ru} (${blockType})` : blockType
}

// ---------------------------------------------------------------------------
// Model recommendations per block category
// ---------------------------------------------------------------------------

/**
 * Per-provider tier → resolved model map. Mirrors ModelPresetResolver.java.
 * Used in SettingsTab and PipelineEditor to show provider-appropriate options.
 */
export const MODEL_TIERS_BY_PROVIDER: Record<string, Array<{ preset: string; label: string; model: string; tier: 'premium' | 'balanced' | 'fast' | 'cheap' }>> = {
  CLAUDE_CODE_CLI: [
    { preset: 'reasoning', label: 'Claude Opus 4.7',   model: 'claude-opus-4-7',    tier: 'premium' },
    { preset: 'smart',     label: 'Claude Sonnet 4.6', model: 'claude-sonnet-4-6',  tier: 'balanced' },
    { preset: 'flash',     label: 'Claude Haiku 4.5',  model: 'claude-haiku-4-5',   tier: 'fast' },
  ],
  OPENROUTER: [
    { preset: 'reasoning', label: 'Gemini 2.5 Pro',        model: 'google/gemini-2.5-pro',           tier: 'premium' },
    { preset: 'smart',     label: 'z-ai/GLM-4.6',          model: 'z-ai/glm-4.6',                    tier: 'balanced' },
    { preset: 'flash',     label: 'z-ai/GLM-4.7 Flash',    model: 'z-ai/glm-4.7-flash',              tier: 'fast' },
    { preset: 'fast',      label: 'Gemini 2.5 Flash Lite', model: 'google/gemini-2.5-flash-lite',     tier: 'fast' },
    { preset: 'cheap',     label: 'GPT-4o mini',           model: 'openai/gpt-4o-mini',               tier: 'cheap' },
  ],
  AITUNNEL: [
    { preset: 'reasoning', label: 'Gemini 2.5 Pro',        model: 'google/gemini-2.5-pro',           tier: 'premium' },
    { preset: 'smart',     label: 'z-ai/GLM-4.6',          model: 'z-ai/glm-4.6',                    tier: 'balanced' },
    { preset: 'flash',     label: 'z-ai/GLM-4.7 Flash',    model: 'z-ai/glm-4.7-flash',              tier: 'fast' },
    { preset: 'fast',      label: 'Gemini 2.5 Flash Lite', model: 'google/gemini-2.5-flash-lite',     tier: 'fast' },
    { preset: 'cheap',     label: 'GPT-4o mini',           model: 'openai/gpt-4o-mini',               tier: 'cheap' },
  ],
  OLLAMA: [
    { preset: 'smart',     label: 'qwen3_cline_roocode:8b', model: 'mychen76/qwen3_cline_roocode:8b', tier: 'balanced' },
  ],
  VLLM: [
    { preset: 'smart',     label: 'Qwen3-4B-AWQ',           model: 'Qwen/Qwen3-4B-AWQ',               tier: 'balanced' },
  ],
}

/**
 * Curated model presets by capability tier.
 * Shown as suggestions in PipelineEditor.
 * Note: preset values (smart/reasoning/etc.) resolve to provider-appropriate models at runtime.
 * See MODEL_TIERS_BY_PROVIDER for per-provider mappings.
 */
export const MODEL_TIERS = [
  {
    preset:      'reasoning',
    model:       'google/gemini-2.5-pro',
    label:       'reasoning',
    tier:        'premium' as const,
    priceHint:   '$$$$',
    description: 'Глубокое рассуждение, сложный анализ (CLI→Opus, OR→Gemini Pro)',
  },
  {
    preset:      'smart',
    model:       'z-ai/glm-4.6',
    label:       'smart',
    tier:        'balanced' as const,
    priceHint:   '$$$',
    description: 'Лучший баланс для кодирования (CLI→Sonnet, OR→GLM-4.6)',
  },
  {
    preset:      'flash',
    model:       'z-ai/glm-4.7-flash',
    label:       'flash',
    tier:        'fast' as const,
    priceHint:   '$$',
    description: 'Быстрый исполнитель (CLI→Haiku, OR→GLM-4.7 Flash)',
  },
  {
    preset:      'gemini-pro',
    model:       'google/gemini-2.5-pro',
    label:       'Gemini 2.5 Pro',
    tier:        'premium' as const,
    priceHint:   '$$$$',
    description: 'Длинный контекст, мультимодальность',
  },
  {
    preset:      'gemini-flash',
    model:       'google/gemini-2.0-flash-001',
    label:       'Gemini 2.0 Flash',
    tier:        'fast' as const,
    priceHint:   '$$',
    description: 'Быстро и дёшево, хорош для пайплайнов',
  },
  {
    preset:      'gpt4o',
    model:       'openai/gpt-4o',
    label:       'GPT-4o',
    tier:        'balanced' as const,
    priceHint:   '$$$',
    description: 'Сильный универсальный, широкая совместимость',
  },
  {
    preset:      'cheap',
    model:       'openai/gpt-4o-mini',
    label:       'GPT-4o mini',
    tier:        'cheap' as const,
    priceHint:   '$',
    description: 'Экономичный, простые задачи',
  },
  {
    preset:      'mistral',
    model:       'mistralai/mistral-large-2411',
    label:       'Mistral Large 2411',
    tier:        'balanced' as const,
    priceHint:   '$$$',
    description: 'Европейская альтернатива, хорош в коде',
  },
  {
    preset:      'qwen',
    model:       'qwen/qwen-2.5-72b-instruct',
    label:       'Qwen 2.5 72B',
    tier:        'cheap' as const,
    priceHint:   '$',
    description: 'Сильный open-source, особенно в коде',
  },
  {
    preset:      'deepseek',
    model:       'deepseek/deepseek-chat-v3-0324',
    label:       'DeepSeek Chat V3',
    tier:        'free' as const,
    priceHint:   '~0',
    description: 'Бесплатный, хорош для кода',
  },
] as const

/** Recommended preset for each block type. */
export const BLOCK_TYPE_RECOMMENDED_PRESET: Record<string, string> = {
  // Requires deep reasoning
  analysis:            'reasoning',
  clarification:       'reasoning',
  business_intake:     'reasoning',
  ai_review:           'reasoning',
  verify:              'smart',

  // Code quality matters most
  code_generation:     'smart',
  test_generation:     'smart',
  agent_with_tools:    'smart',

  // Light tasks
  release_notes:       'gemini-flash',
  task_md_input:       'gemini-flash',
  shell_exec:          'gemini-flash',

  // No LLM
  build:               'gemini-flash',
  deploy:              'gemini-flash',
  run_tests:           'gemini-flash',
}

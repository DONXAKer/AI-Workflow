import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

const sampleProfiles = [
  {
    id: 1, name: 'analyst', displayName: 'Software Analyst',
    description: 'Глубоко анализирует требования и риски.',
    rolePrompt: 'Ты senior analyst...', customPrompt: '',
    model: 'claude-opus-4-7', maxTokens: 8192, temperature: 1.0,
    skills: ['read_file', 'search_code', 'query_knowledge_base'],
    knowledgeSources: ['architecture_docs'], useExamples: true,
    recommendedPreset: 'reasoning', builtin: true,
  },
  {
    id: 2, name: 'custom-coder', displayName: 'Custom Coder',
    description: 'Кастомный профиль для команды мобильной разработки.',
    rolePrompt: 'You are a senior iOS engineer...', customPrompt: '',
    model: 'claude-sonnet-4-6', maxTokens: 8192, temperature: 0.7,
    skills: ['read_file', 'write_file'],
    knowledgeSources: ['mobile_architecture', 'ios_style_guide'], useExamples: false,
    recommendedPreset: 'smart', builtin: false,
  },
]

test.describe('AgentProfilesSettings — новые поля', () => {
  test('built-in-бейдж рендерится для built-in профиля', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/agent-profiles', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sampleProfiles) })
    })
    await page.route('**/api/agent-profiles/skills', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    })
    await page.goto('/system/agent-profiles')
    await expect(page.locator('tr', { hasText: 'analyst' }).getByText('built-in')).toBeVisible()
    await expect(page.locator('tr', { hasText: 'analyst' }).getByText('few-shot')).toBeVisible()
    // У custom-coder нет built-in/few-shot бейджей
    await expect(page.locator('tr', { hasText: 'custom-coder' }).getByText('built-in')).toHaveCount(0)
    await expect(page.locator('tr', { hasText: 'custom-coder' }).getByText('few-shot')).toHaveCount(0)
  })

  test('форма редактирования показывает knowledgeSources и preset', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/agent-profiles', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sampleProfiles) })
    })
    await page.route('**/api/agent-profiles/skills', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    })
    await page.goto('/system/agent-profiles')
    await page.locator('tr', { hasText: 'custom-coder' }).getByRole('button', { name: 'Изменить' }).click()
    await expect(page.getByLabel('Источники знаний')).toHaveValue('mobile_architecture, ios_style_guide')
    await expect(page.getByLabel('Рекомендованный пресет')).toHaveValue('smart')
    await expect(page.getByText('Использовать few-shot-примеры')).toBeVisible()
  })

  test('built-in профиль показывает предупреждение при редактировании', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/agent-profiles', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sampleProfiles) })
    })
    await page.route('**/api/agent-profiles/skills', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    })
    await page.goto('/system/agent-profiles')
    await page.locator('tr', { hasText: 'analyst' }).getByRole('button', { name: 'Изменить' }).click()
    await expect(page.getByText(/встроенный профиль/i)).toBeVisible()
  })
})

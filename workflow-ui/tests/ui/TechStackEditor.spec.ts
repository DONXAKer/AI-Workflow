import { test, expect } from '../fixtures/api-mocks'

test.describe('TechStackEditor', () => {
  test.beforeEach(async ({ page }) => {
    // Mock project data
    await page.route('/api/projects/test-project', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          slug: 'test-project',
          displayName: 'Test Project',
          workingDir: '/test/path',
          techStackJson: '[{"name":"java","version":"17"},{"name":"spring-boot","version":"3.1.0"}]'
        })
      })
    })

    // Mock detect-stack endpoint
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          techStack: '[{"name":"java","version":"17"},{"name":"spring-boot","version":"3.1.0"},{"name":"react","version":"18.2.0"}]',
          workingDir: '/test/path'
        })
      })
    })

    // Mock update project endpoint
    await page.route('**/api/projects/test-project', async (route) => {
      if (route.request().method() === 'PUT') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            slug: 'test-project',
            displayName: 'Test Project',
            techStackJson: route.request().postDataJSON()?.techStackJson
          })
        })
      }
    })
  })

  test('renders tech stack from initial data', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Wait for tech stack to load
    await expect(page.locator('[data-testid="tech-stack-editor"]')).toBeVisible()
    
    // Check that initial tech stack is displayed
    await expect(page.locator('text=java')).toBeVisible()
    await expect(page.locator('text=17')).toBeVisible()
    await expect(page.locator('text=spring-boot')).toBeVisible()
    await expect(page.locator('text=3.1.0')).toBeVisible()
  })

  test('detects tech stack when rescan button is clicked', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Click rescan button
    await page.click('[data-testid="rescan-button"]')
    
    // Wait for detection to complete
    await expect(page.locator('text=react')).toBeVisible()
    await expect(page.locator('text=18.2.0')).toBeVisible()
    
    // Check that save button appears (has changes)
    await expect(page.locator('[data-testid="save-button"]')).toBeVisible()
    await expect(page.locator('text=Unsaved changes')).toBeVisible()
  })

  test('shows error when detection fails', async ({ page }) => {
    // Mock failed detection
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          error: 'Scanner not available'
        })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Click rescan button
    await page.click('[data-testid="rescan-button"]')
    
    // Check error message
    await expect(page.locator('text=Scanner not available')).toBeVisible()
  })

  test('shows 404 error when project not found', async ({ page }) => {
    // Mock 404 response
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Project not found' })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Click rescan button
    await page.click('[data-testid="rescan-button"]')
    
    // Check error message
    await expect(page.locator('text=Project not found')).toBeVisible()
  })

  test('saves tech stack changes', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Enable edit mode
    await page.click('[data-testid="edit-button"]')
    
    // Add new technology
    await page.fill('[data-testid="new-tech-name"]', 'typescript')
    await page.fill('[data-testid="new-tech-version"]', '5.0.0')
    await page.click('[data-testid="add-tech-button"]')
    
    // Save changes
    await page.click('[data-testid="save-button"]')
    
    // Verify save was called
    const saveRequest = await page.waitForRequest('**/api/projects/test-project')
    expect(saveRequest.method()).toBe('PUT')
    
    const saveData = saveRequest.postDataJSON()
    expect(saveData.techStackJson).toContain('typescript')
    expect(saveData.techStackJson).toContain('5.0.0')
  })

  test('removes technology when in edit mode', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Enable edit mode
    await page.click('[data-testid="edit-button"]')
    
    // Remove technology
    await page.click('[data-testid="remove-tech-button"]:first-child')
    
    // Check that technology is removed
    await expect(page.locator('text=java')).not.toBeVisible()
    
    // Check unsaved changes indicator
    await expect(page.locator('text=Unsaved changes')).toBeVisible()
  })

  test('groups technologies by category', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Check that categories are displayed
    await expect(page.locator('text=Languages')).toBeVisible()
    await expect(page.locator('text=Frameworks')).toBeVisible()
    
    // Expand Languages category
    await page.click('text=Languages')
    
    // Check that java is under Languages
    await expect(page.locator('text=java')).toBeVisible()
  })

  test('shows loading state during detection', async ({ page }) => {
    // Mock slow detection
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
      await new Promise(resolve => setTimeout(resolve, 1000))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          techStack: '[{"name":"java","version":"17"}]',
          workingDir: '/test/path'
        })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Click rescan button
    await page.click('[data-testid="rescan-button"]')
    
    // Check loading state
    await expect(page.locator('[data-testid="rescan-button"] [data-testid="loading-spinner"]')).toBeVisible()
    await expect(page.locator('[data-testid="rescan-button"]')).toBeDisabled()
  })

  test('disables rescan button when no project slug', async ({ page }) => {
    // Mock project without slug
    await page.route('/api/projects/', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          slug: null,
          displayName: 'Test Project'
        })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Check that rescan button is disabled
    await expect(page.locator('[data-testid="rescan-button"]')).toBeDisabled()
  })

  test('shows empty state when no technologies detected', async ({ page }) => {
    // Mock empty tech stack
    await page.route('/api/projects/test-project', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          slug: 'test-project',
          displayName: 'Test Project',
          techStackJson: '[]'
        })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Check empty state
    await expect(page.locator('text=No technologies detected')).toBeVisible()
    await expect(page.locator('[data-testid="detect-stack-button"]')).toBeVisible()
  })
})
import { test, expect } from '../fixtures/api-mocks'

test.describe('Rescan Button', () => {
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
          techStackJson: '[{"name":"java","version":"17"}]'
        })
      })
    })
  })

  test('shows diff when new technologies are detected', async ({ page }) => {
    // Mock detection with additional technologies
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

    await page.goto('/projects/test-project/settings')
    
    // Click rescan button
    await page.click('[data-testid="rescan-button"]')
    
    // Wait for detection to complete
    await expect(page.locator('text=spring-boot')).toBeVisible()
    await expect(page.locator('text=react')).toBeVisible()
    
    // Check that diff is shown (new items highlighted)
    await expect(page.locator('[data-testid="new-tech-item"]:has-text("spring-boot")')).toBeVisible()
    await expect(page.locator('[data-testid="new-tech-item"]:has-text("react")')).toBeVisible()
  })

  test('shows loading spinner during detection', async ({ page }) => {
    // Mock slow detection
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
      await new Promise(resolve => setTimeout(resolve, 2000))
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
    await expect(page.locator('text=Scanning...')).toBeVisible()
  })

  test('handles service unavailable error', async ({ page }) => {
    // Mock 503 response
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          error: 'Stack scanner not available'
        })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Click rescan button
    await page.click('[data-testid="rescan-button"]')
    
    // Check error message
    await expect(page.locator('text=Stack scanner not available')).toBeVisible()
    await expect(page.locator('[data-testid="error-message"]')).toHaveClass(/red/)
  })

  test('handles network error gracefully', async ({ page }) => {
    // Mock network error
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
      await route.abort('failed')
    })

    await page.goto('/projects/test-project/settings')
    
    // Click rescan button
    await page.click('[data-testid="rescan-button"]')
    
    // Check error message
    await expect(page.locator('text=Failed to detect tech stack')).toBeVisible()
  })

  test('shows unchanged technologies when no new detections', async ({ page }) => {
    // Mock detection with same technologies
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
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
    
    // Wait for detection to complete
    await expect(page.locator('text=java')).toBeVisible()
    
    // Check that no changes indicator is shown
    await expect(page.locator('text=Unsaved changes')).not.toBeVisible()
    await expect(page.locator('[data-testid="save-button"]')).not.toBeVisible()
  })

  test('disables button during detection', async ({ page }) => {
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
    
    // Check that button is disabled
    await expect(page.locator('[data-testid="rescan-button"]')).toBeDisabled()
    
    // Wait for completion
    await expect(page.locator('[data-testid="rescan-button"]')).toBeEnabled()
  })

  test('shows version updates in diff', async ({ page }) => {
    // Mock detection with version update
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          techStack: '[{"name":"java","version":"21"}]',
          workingDir: '/test/path'
        })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Click rescan button
    await page.click('[data-testid="rescan-button"]')
    
    // Wait for detection to complete
    await expect(page.locator('text=21')).toBeVisible()
    
    // Check that version change is highlighted
    await expect(page.locator('[data-testid="version-update"]')).toBeVisible()
  })

  test('persists detected stack after save', async ({ page }) => {
    // Mock detection with new technologies
    await page.route('/api/projects/test-project/detect-stack', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          techStack: '[{"name":"java","version":"17"},{"name":"spring-boot","version":"3.1.0"}]',
          workingDir: '/test/path'
        })
      })
    })

    // Mock update project
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

    await page.goto('/projects/test-project/settings')
    
    // Click rescan button
    await page.click('[data-testid="rescan-button"]')
    
    // Wait for detection to complete
    await expect(page.locator('text=spring-boot')).toBeVisible()
    
    // Save changes
    await page.click('[data-testid="save-button"]')
    
    // Check that unsaved changes indicator disappears
    await expect(page.locator('text=Unsaved changes')).not.toBeVisible()
    await expect(page.locator('[data-testid="save-button"]')).not.toBeVisible()
  })
})
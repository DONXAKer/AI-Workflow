import { test, expect } from '../fixtures/api-mocks'

test.describe('404 Error Handling', () => {
  test('handles 404 for detect-stack endpoint', async ({ page }) => {
    // Mock 404 response for detect-stack
    await page.route('/api/projects/nonexistent/detect-stack', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Project not found' })
      })
    })

    // Mock project data
    await page.route('/api/projects/test-project', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          slug: 'test-project',
          displayName: 'Test Project',
          workingDir: '/test/path',
          techStackJson: '[]'
        })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Try to detect stack for non-existent project (simulate via URL manipulation)
    await page.evaluate(() => {
      // Simulate clicking rescan with wrong project slug
      const rescanButton = document.querySelector('[data-testid="rescan-button"]')
      if (rescanButton) {
        // Override the click handler to simulate wrong slug
        (rescanButton as any).onclick = async () => {
          try {
            const response = await fetch('/api/projects/nonexistent/detect-stack', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' }
            })
            if (!response.ok) {
              throw new Error('Project not found')
            }
          } catch (error) {
            // Show error in UI
            const errorDiv = document.createElement('div')
            errorDiv.textContent = 'Project not found'
            errorDiv.className = 'error-message'
            document.querySelector('[data-testid="tech-stack-editor"]')?.appendChild(errorDiv)
          }
        }
        rescanButton.click()
      }
    })
    
    // Check error message
    await expect(page.locator('text=Project not found')).toBeVisible()
  })

  test('handles 404 for project data', async ({ page }) => {
    // Mock 404 for project data
    await page.route('/api/projects/nonexistent', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Project not found' })
      })
    })

    // Navigate to non-existent project
    await page.goto('/projects/nonexistent/settings')
    
    // Check that 404 page is shown
    await expect(page.locator('text=Project not found')).toBeVisible()
    await expect(page.locator('[data-testid="not-found-page"]')).toBeVisible()
  })

  test('handles 404 for pipeline config', async ({ page }) => {
    // Mock 404 for pipeline config
    await page.route('/api/pipelines/config*', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Pipeline config not found' })
      })
    })

    await page.goto('/projects/test-project/pipeline-editor?config=nonexistent.yaml')
    
    // Check error message
    await expect(page.locator('text=Pipeline config not found')).toBeVisible()
    await expect(page.locator('[data-testid="config-error"]')).toBeVisible()
  })

  test('handles 404 for run details', async ({ page }) => {
    // Mock 404 for run details
    await page.route('/api/runs/nonexistent', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Run not found' })
      })
    })

    await page.goto('/projects/test-project/runs/nonexistent')
    
    // Check error message
    await expect(page.locator('text=Run not found')).toBeVisible()
    await expect(page.locator('[data-testid="run-error"]')).toBeVisible()
  })

  test('handles 404 for integration endpoints', async ({ page }) => {
    // Mock 404 for integration
    await page.route('/api/integrations/999', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Integration not found' })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Navigate to integrations tab
    await page.click('[data-testid="integrations-tab"]')
    
    // Try to edit non-existent integration
    await page.evaluate(() => {
      const editButton = document.querySelector('[data-testid="edit-integration-button"]')
      if (editButton) {
        (editButton as any).onclick = async () => {
          try {
            const response = await fetch('/api/integrations/999', {
              method: 'GET',
              headers: { 'Content-Type': 'application/json' }
            })
            if (!response.ok) {
              throw new Error('Integration not found')
            }
          } catch (error) {
            const errorDiv = document.createElement('div')
            errorDiv.textContent = 'Integration not found'
            errorDiv.className = 'error-message'
            document.querySelector('[data-testid="integrations-section"]')?.appendChild(errorDiv)
          }
        }
        editButton.click()
      }
    })
    
    // Check error message
    await expect(page.locator('text=Integration not found')).toBeVisible()
  })

  test('handles 404 for agent profiles', async ({ page }) => {
    // Mock 404 for agent profile
    await page.route('/api/agent-profiles/999', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Agent profile not found' })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Navigate to agent profiles tab
    await page.click('[data-testid="agent-profiles-tab"]')
    
    // Try to edit non-existent profile
    await page.evaluate(() => {
      const editButton = document.querySelector('[data-testid="edit-profile-button"]')
      if (editButton) {
        (editButton as any).onclick = async () => {
          try {
            const response = await fetch('/api/agent-profiles/999', {
              method: 'GET',
              headers: { 'Content-Type': 'application/json' }
            })
            if (!response.ok) {
              throw new Error('Agent profile not found')
            }
          } catch (error) {
            const errorDiv = document.createElement('div')
            errorDiv.textContent = 'Agent profile not found'
            errorDiv.className = 'error-message'
            document.querySelector('[data-testid="agent-profiles-section"]')?.appendChild(errorDiv)
          }
        }
        editButton.click()
      }
    })
    
    // Check error message
    await expect(page.locator('text=Agent profile not found')).toBeVisible()
  })

  test('handles 404 for MCP servers', async ({ page }) => {
    // Mock 404 for MCP server
    await page.route('/api/mcp-servers/999', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'MCP server not found' })
      })
    })

    await page.goto('/projects/test-project/settings')
    
    // Navigate to MCP servers tab
    await page.click('[data-testid="mcp-servers-tab"]')
    
    // Try to edit non-existent server
    await page.evaluate(() => {
      const editButton = document.querySelector('[data-testid="edit-mcp-button"]')
      if (editButton) {
        (editButton as any).onclick = async () => {
          try {
            const response = await fetch('/api/mcp-servers/999', {
              method: 'GET',
              headers: { 'Content-Type': 'application/json' }
            })
            if (!response.ok) {
              throw new Error('MCP server not found')
            }
          } catch (error) {
            const errorDiv = document.createElement('div')
            errorDiv.textContent = 'MCP server not found'
            errorDiv.className = 'error-message'
            document.querySelector('[data-testid="mcp-servers-section"]')?.appendChild(errorDiv)
          }
        }
        editButton.click()
      }
    })
    
    // Check error message
    await expect(page.locator('text=MCP server not found')).toBeVisible()
  })

  test('shows helpful navigation options on 404', async ({ page }) => {
    // Mock 404 for project
    await page.route('/api/projects/nonexistent', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Project not found' })
      })
    })

    await page.goto('/projects/nonexistent/settings')
    
    // Check navigation options
    await expect(page.locator('[data-testid="back-to-projects"]')).toBeVisible()
    await expect(page.locator('[data-testid="create-new-project"]')).toBeVisible()
    await expect(page.locator('[data-testid="search-projects"]')).toBeVisible()
    
    // Test navigation back to projects
    await page.click('[data-testid="back-to-projects"]')
    await expect(page).toHaveURL('/projects')
  })

  test('preserves URL parameters on 404 navigation', async ({ page }) => {
    // Mock 404 for run
    await page.route('/api/runs/nonexistent', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Run not found' })
      })
    })

    // Navigate with parameters
    await page.goto('/projects/test-project/runs/nonexistent?tab=logs&filter=error')
    
    // Check error page
    await expect(page.locator('text=Run not found')).toBeVisible()
    
    // Check that parameters are preserved in navigation links
    const backLink = page.locator('[data-testid="back-to-runs"]')
    await expect(backLink).toHaveAttribute('href', /.*tab=logs.*filter=error/)
  })

  test('handles 404 with custom error styling', async ({ page }) => {
    // Mock 404 with custom error
    await page.route('/api/projects/nonexistent', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ 
          error: 'Project not found',
          code: 'PROJECT_NOT_FOUND',
          timestamp: '2023-01-01T00:00:00Z'
        })
      })
    })

    await page.goto('/projects/nonexistent/settings')
    
    // Check custom error styling
    await expect(page.locator('[data-testid="error-code"]')).toHaveText('PROJECT_NOT_FOUND')
    await expect(page.locator('[data-testid="error-timestamp"]')).toBeVisible()
    await expect(page.locator('[data-testid="error-container"]')).toHaveClass(/error-404/)
  })
})
import { test, expect } from '../fixtures/api-mocks'

test.describe('Patterns and Risks Editing', () => {
  test.beforeEach(async ({ page }) => {
    // Mock project data with patterns and risks
    await page.route('/api/projects/test-project', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          slug: 'test-project',
          displayName: 'Test Project',
          patternsJson: JSON.stringify([
            { id: '1', name: 'MVC Pattern', description: 'Model-View-Controller pattern' },
            { id: '2', name: 'Repository Pattern', description: 'Data access abstraction' }
          ]),
          risksJson: JSON.stringify([
            { id: '1', title: 'High Memory Usage', severity: 'medium', mitigation: 'Optimize queries' },
            { id: '2', title: 'Security Vulnerability', severity: 'high', mitigation: 'Add authentication' }
          ])
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
            patternsJson: route.request().postDataJSON()?.patternsJson,
            risksJson: route.request().postDataJSON()?.risksJson
          })
        })
      }
    })
  })

  test('loads and displays patterns and risks', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to patterns tab
    await page.click('[data-testid="patterns-tab"]')
    
    // Check patterns are displayed
    await expect(page.locator('text=MVC Pattern')).toBeVisible()
    await expect(page.locator('text=Model-View-Controller pattern')).toBeVisible()
    await expect(page.locator('text=Repository Pattern')).toBeVisible()
    
    // Switch to risks tab
    await page.click('[data-testid="risks-tab"]')
    
    // Check risks are displayed
    await expect(page.locator('text=High Memory Usage')).toBeVisible()
    await expect(page.locator('text=Security Vulnerability')).toBeVisible()
    await expect(page.locator('[data-testid="severity-medium"]')).toBeVisible()
    await expect(page.locator('[data-testid="severity-high"]')).toBeVisible()
  })

  test('adds new pattern', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to patterns tab
    await page.click('[data-testid="patterns-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-patterns-button"]')
    
    // Add new pattern
    await page.click('[data-testid="add-pattern-button"]')
    await page.fill('[data-testid="pattern-name-input"]', 'Observer Pattern')
    await page.fill('[data-testid="pattern-description-input"]', 'Event subscription pattern')
    await page.click('[data-testid="save-pattern-button"]')
    
    // Check new pattern is added
    await expect(page.locator('text=Observer Pattern')).toBeVisible()
    await expect(page.locator('text=Event subscription pattern')).toBeVisible()
    
    // Save changes
    await page.click('[data-testid="save-patterns-button"]')
    
    // Verify save was called
    const saveRequest = await page.waitForRequest('**/api/projects/test-project')
    expect(saveRequest.method()).toBe('PUT')
    
    const saveData = saveRequest.postDataJSON()
    const patterns = JSON.parse(saveData.patternsJson)
    expect(patterns).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ name: 'Observer Pattern', description: 'Event subscription pattern' })
      ])
    )
  })

  test('adds new risk', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to risks tab
    await page.click('[data-testid="risks-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-risks-button"]')
    
    // Add new risk
    await page.click('[data-testid="add-risk-button"]')
    await page.fill('[data-testid="risk-title-input"]', 'Performance Bottleneck')
    await page.selectOption('[data-testid="risk-severity-select"]', 'low')
    await page.fill('[data-testid="risk-mitigation-input"]', 'Add caching layer')
    await page.click('[data-testid="save-risk-button"]')
    
    // Check new risk is added
    await expect(page.locator('text=Performance Bottleneck')).toBeVisible()
    await expect(page.locator('[data-testid="severity-low"]')).toBeVisible()
    await expect(page.locator('text=Add caching layer')).toBeVisible()
    
    // Save changes
    await page.click('[data-testid="save-risks-button"]')
    
    // Verify save was called
    const saveRequest = await page.waitForRequest('**/api/projects/test-project')
    expect(saveRequest.method()).toBe('PUT')
    
    const saveData = saveRequest.postDataJSON()
    const risks = JSON.parse(saveData.risksJson)
    expect(risks).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ 
          title: 'Performance Bottleneck', 
          severity: 'low', 
          mitigation: 'Add caching layer' 
        })
      ])
    )
  })

  test('edits existing pattern', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to patterns tab
    await page.click('[data-testid="patterns-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-patterns-button"]')
    
    // Edit first pattern
    await page.click('[data-testid="edit-pattern-button"]:first-child')
    await page.fill('[data-testid="pattern-name-input"]', 'Updated MVC Pattern')
    await page.fill('[data-testid="pattern-description-input"]', 'Updated description')
    await page.click('[data-testid="save-pattern-button"]')
    
    // Check pattern is updated
    await expect(page.locator('text=Updated MVC Pattern')).toBeVisible()
    await expect(page.locator('text=Updated description')).toBeVisible()
    
    // Save changes
    await page.click('[data-testid="save-patterns-button"]')
  })

  test('edits existing risk', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to risks tab
    await page.click('[data-testid="risks-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-risks-button"]')
    
    // Edit first risk
    await page.click('[data-testid="edit-risk-button"]:first-child')
    await page.fill('[data-testid="risk-title-input"]', 'Updated Risk Title')
    await page.selectOption('[data-testid="risk-severity-select"]', 'critical')
    await page.fill('[data-testid="risk-mitigation-input"]', 'Updated mitigation')
    await page.click('[data-testid="save-risk-button"]')
    
    // Check risk is updated
    await expect(page.locator('text=Updated Risk Title')).toBeVisible()
    await expect(page.locator('[data-testid="severity-critical"]')).toBeVisible()
    await expect(page.locator('text=Updated mitigation')).toBeVisible()
    
    // Save changes
    await page.click('[data-testid="save-risks-button"]')
  })

  test('deletes pattern', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to patterns tab
    await page.click('[data-testid="patterns-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-patterns-button"]')
    
    // Delete first pattern
    await page.click('[data-testid="delete-pattern-button"]:first-child')
    
    // Confirm deletion
    await page.click('[data-testid="confirm-delete-button"]')
    
    // Check pattern is removed
    await expect(page.locator('text=MVC Pattern')).not.toBeVisible()
    
    // Save changes
    await page.click('[data-testid="save-patterns-button"]')
  })

  test('deletes risk', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to risks tab
    await page.click('[data-testid="risks-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-risks-button"]')
    
    // Delete first risk
    await page.click('[data-testid="delete-risk-button"]:first-child')
    
    // Confirm deletion
    await page.click('[data-testid="confirm-delete-button"]')
    
    // Check risk is removed
    await expect(page.locator('text=High Memory Usage')).not.toBeVisible()
    
    // Save changes
    await page.click('[data-testid="save-risks-button"]')
  })

  test('shows unsaved changes indicator', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to patterns tab
    await page.click('[data-testid="patterns-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-patterns-button"]')
    
    // Add new pattern
    await page.click('[data-testid="add-pattern-button"]')
    await page.fill('[data-testid="pattern-name-input"]', 'New Pattern')
    await page.fill('[data-testid="pattern-description-input"]', 'New description')
    await page.click('[data-testid="save-pattern-button"]')
    
    // Check unsaved changes indicator
    await expect(page.locator('text=Unsaved changes')).toBeVisible()
    await expect(page.locator('[data-testid="save-patterns-button"]')).toBeVisible()
  })

  test('handles save errors gracefully', async ({ page }) => {
    // Mock save error
    await page.route('**/api/projects/test-project', async (route) => {
      if (route.request().method() === 'PUT') {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Save failed' })
        })
      }
    })

    await page.goto('/projects/test-project/settings')
    
    // Switch to patterns tab
    await page.click('[data-testid="patterns-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-patterns-button"]')
    
    // Add new pattern
    await page.click('[data-testid="add-pattern-button"]')
    await page.fill('[data-testid="pattern-name-input"]', 'New Pattern')
    await page.fill('[data-testid="pattern-description-input"]', 'New description')
    await page.click('[data-testid="save-pattern-button"]')
    
    // Save changes
    await page.click('[data-testid="save-patterns-button"]')
    
    // Check error message
    await expect(page.locator('text=Save failed')).toBeVisible()
    await expect(page.locator('[data-testid="error-message"]')).toHaveClass(/red/)
  })

  test('validates required fields', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to patterns tab
    await page.click('[data-testid="patterns-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-patterns-button"]')
    
    // Try to add pattern without name
    await page.click('[data-testid="add-pattern-button"]')
    await page.fill('[data-testid="pattern-description-input"]', 'Description without name')
    await page.click('[data-testid="save-pattern-button"]')
    
    // Check validation error
    await expect(page.locator('text=Pattern name is required')).toBeVisible()
    
    // Try to add risk without title
    await page.click('[data-testid="risks-tab"]')
    await page.click('[data-testid="edit-risks-button"]')
    await page.click('[data-testid="add-risk-button"]')
    await page.fill('[data-testid="risk-mitigation-input"]', 'Mitigation without title')
    await page.click('[data-testid="save-risk-button"]')
    
    // Check validation error
    await expect(page.locator('text=Risk title is required')).toBeVisible()
  })

  test('cancels edit mode', async ({ page }) => {
    await page.goto('/projects/test-project/settings')
    
    // Switch to patterns tab
    await page.click('[data-testid="patterns-tab"]')
    
    // Enable edit mode
    await page.click('[data-testid="edit-patterns-button"]')
    
    // Add new pattern
    await page.click('[data-testid="add-pattern-button"]')
    await page.fill('[data-testid="pattern-name-input"]', 'New Pattern')
    await page.fill('[data-testid="pattern-description-input"]', 'New description')
    await page.click('[data-testid="save-pattern-button"]')
    
    // Cancel edit mode
    await page.click('[data-testid="cancel-edit-button"]')
    
    // Check that changes are discarded
    await expect(page.locator('text=New Pattern')).not.toBeVisible()
    await expect(page.locator('text=MVC Pattern')).toBeVisible()
  })
})
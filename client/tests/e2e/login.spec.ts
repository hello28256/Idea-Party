import { test, expect } from '@playwright/test'

test.describe('Login Flow', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to login page
    await page.goto('/login')
  })

  test('should show login page with form elements', async ({ page }) => {
    // Check page title or heading
    await expect(page.locator('h1, h2, .title, [data-testid="title"]')).toBeVisible()

    // Check form elements exist
    await expect(page.locator('input[type="email"], input[name="email"]')).toBeVisible()
    await expect(page.locator('input[type="password"], input[name="password"]')).toBeVisible()
    await expect(page.locator('button[type="submit"]')).toBeVisible()
  })

  test('should login with valid credentials', async ({ page }) => {
    // Fill login form
    const emailInput = page.locator('input[type="email"], input[name="email"]')
    const passwordInput = page.locator('input[type="password"], input[name="password"]')
    const submitButton = page.locator('button[type="submit"]')

    await emailInput.fill('test@example.com')
    await passwordInput.fill('password123')
    await submitButton.click()

    // Should redirect to home or room list after login
    // The exact URL depends on the app's routing
    await expect(page).not.toHaveURL(/\/login/)
  })

  test('should show error with invalid credentials', async ({ page }) => {
    // Fill login form with invalid credentials
    const emailInput = page.locator('input[type="email"], input[name="email"]')
    const passwordInput = page.locator('input[type="password"], input[name="password"]')
    const submitButton = page.locator('button[type="submit"]')

    await emailInput.fill('invalid@example.com')
    await passwordInput.fill('wrongpassword')
    await submitButton.click()

    // Should show error message
    await expect(page.locator('.error, [data-testid="error"], .text-red, .text-red-500')).toBeVisible()
  })

  test('should redirect to login when accessing protected route', async ({ page }) => {
    // Try to access a protected route directly
    await page.goto('/rooms')

    // Should redirect to login
    await expect(page).toHaveURL(/\/login/)
  })

  test('should navigate to register page', async ({ page }) => {
    // Look for register link
    const registerLink = page.locator('a[href*="register"], button:has-text("Register"), text="Register"')

    if (await registerLink.isVisible()) {
      await registerLink.click()
      await expect(page).toHaveURL(/\/register/)
    }
  })
})

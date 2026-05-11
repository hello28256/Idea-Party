import { test, expect } from '@playwright/test'

test.describe('Room Creation Flow', () => {
  // Helper to login before room tests
  test.beforeEach(async ({ page }) => {
    // Navigate to login and login first
    await page.goto('/login')

    const emailInput = page.locator('input[type="email"], input[name="email"]')
    const passwordInput = page.locator('input[type="password"], input[name="password"]')
    const submitButton = page.locator('button[type="submit"]')

    await emailInput.fill('test@example.com')
    await passwordInput.fill('password123')
    await submitButton.click()

    // Wait for redirect after login
    await page.waitForURL(/\/(rooms|home)?/, { timeout: 5000 }).catch(() => {
      // If already logged in, just continue
    })
  })

  test('should show room list page', async ({ page }) => {
    // Check for room list elements
    const roomList = page.locator('[data-testid="room-list"], .room-list, .rooms')
    await expect(roomList.first()).toBeVisible({ timeout: 5000 })
  })

  test('should create new room', async ({ page }) => {
    // Generate unique room name
    const uniqueRoomName = `Test Room ${Date.now()}`

    // Look for create room button
    const createButton = page.locator('button:has-text("Create"), [data-testid="create-room"]')
    await createButton.click()

    // Wait for modal to appear
    const modal = page.locator('.modal, [data-testid="create-room-modal"]')
    await expect(modal).toBeVisible({ timeout: 3000 })

    // Fill in room name
    const nameInput = modal.locator('input[name="name"], input[placeholder*="room"]')
    await nameInput.fill(uniqueRoomName)

    // Submit
    const submitButton = modal.locator('button[type="submit"], button:has-text("Create")')
    await submitButton.click()

    // Verify room appears in list
    await expect(page.locator(`text=${uniqueRoomName}`)).toBeVisible({ timeout: 5000 })
  })

  test('should show room in list after creation', async ({ page }) => {
    // Generate unique room name
    const uniqueRoomName = `E2E Test Room ${Date.now()}`

    // Click create button
    const createButton = page.locator('button:has-text("Create")').first()
    await createButton.click()

    // Fill modal
    const modal = page.locator('.modal')
    await expect(modal).toBeVisible()
    const nameInput = modal.locator('input[name="name"]')
    await nameInput.fill(uniqueRoomName)

    // Submit
    await modal.locator('button:has-text("Create")').click()

    // Verify in list
    const roomItem = page.locator(`.room-item, [data-testid="room"], text=${uniqueRoomName}`)
    await expect(roomItem).toBeVisible({ timeout: 5000 })
  })

  test('should navigate to room after creation', async ({ page }) => {
    // Generate unique room name
    const uniqueRoomName = `Navigate Test ${Date.now()}`

    // Create room
    const createButton = page.locator('button:has-text("Create")').first()
    await createButton.click()

    const modal = page.locator('.modal')
    await expect(modal).toBeVisible()
    await modal.locator('input[name="name"]').fill(uniqueRoomName)
    await modal.locator('button:has-text("Create")').click()

    // Click on the created room
    const roomLink = page.locator(`text=${uniqueRoomName}`).first()
    await roomLink.click()

    // Should navigate to room view
    await expect(page).toHaveURL(/\/room\/|chat/)
  })
})

import { test, expect } from '@playwright/test';

test.describe('Room Creation', () => {
  test('should create a new chat room and navigate to chat view', async ({ page }) => {
    const apiLogs: { method: string; url: string; status: number; body?: string }[] = [];

    // Capture API responses
    page.on('response', async res => {
      if (res.url().includes('localhost:8080/api')) {
        const body = await res.text().catch(() => '');
        apiLogs.push({
          method: res.request().method(),
          url: res.url(),
          status: res.status(),
          body: body.substring(0, 300)
        });
      }
    });

    // Navigate to login
    await page.goto('http://localhost:5173/login');

    // Login
    await page.fill('input[type="email"]', 'final@example.com');
    await page.fill('input[type="password"]', 'password123');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/rooms', { timeout: 10000 });
    await page.waitForTimeout(1000);

    // Click create button
    await page.locator('button:has-text("创建房间"), button:has-text("创建第一个聊天室")').first().click();
    await page.waitForTimeout(500);

    // Fill room name
    await page.locator('input[placeholder*="哲学讨论群"]').fill('E2E Test Room ' + Date.now());

    // Submit
    await page.locator('button[type="submit"]:has-text("创建")').click();

    // Wait for navigation to chat view
    await page.waitForURL('**/chat/**', { timeout: 10000 });
    console.log('\n✓ Navigated to chat view:', page.url());

    // Wait for chat to load
    await page.waitForTimeout(2000);

    // Print relevant API logs
    console.log('\n=== Key API Logs ===');
    apiLogs.filter(l => l.status !== 200 || l.url.includes('rooms')).forEach(log => {
      console.log(`${log.method} ${log.status} ${log.url}`);
      if (log.body) console.log(`  Body: ${log.body}`);
    });

    // Check if we see the chat interface
    const chatHeader = page.locator('h1, h2').first();
    const headerVisible = await chatHeader.isVisible({ timeout: 3000 }).catch(() => false);
    console.log(`\nChat header visible: ${headerVisible}`);

    if (headerVisible) {
      const headerText = await chatHeader.textContent();
      console.log(`Header text: ${headerText}`);
    }

    // Verify the room was created successfully
    const roomCreatedLog = apiLogs.find(l => l.method === 'POST' && l.url.includes('/rooms') && l.status === 201);
    if (roomCreatedLog) {
      console.log('\n✓ Room created successfully!');
      console.log('  Room ID:', roomCreatedLog.body?.match(/"id":"([^"]+)"/)?.[1]);
    } else {
      console.log('\n✗ Room creation failed - no 201 response');
    }

    expect(roomCreatedLog).toBeDefined();
  });
});

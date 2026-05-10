import { chromium } from 'playwright';

const BASE_URL = 'http://localhost:5173';
const TEST_EMAIL = `playwright_${Date.now()}@test.com`;
const TEST_PASSWORD = 'test123456';

async function test() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();

  // Collect console errors
  const consoleErrors = [];
  const wsMessages = [];
  page.on('console', msg => {
    if (msg.type() === 'error') {
      consoleErrors.push(msg.text());
    }
    if (msg.text().startsWith('[WS]')) {
      wsMessages.push(msg.text());
    }
  });

  // Inject WebSocket interceptor before page loads
  await page.addInitScript(() => {
    window.__wsMessages = [];
    const originalWebSocket = window.WebSocket;
    window.WebSocket = function(url, protocols) {
      const ws = new originalWebSocket(url, protocols);
      console.log('[WS] Opened:', url);
      const originalSend = ws.send.bind(ws);
      ws.send = function(data) {
        console.log('[WS] SEND:', data);
        window.__wsMessages.push('SEND: ' + data);
        return originalSend(data);
      };
      const originalOnMessage = ws.onmessage;
      ws.onmessage = function(event) {
        console.log('[WS] RECV:', event.data);
        window.__wsMessages.push('RECV: ' + event.data);
        if (originalOnMessage) originalOnMessage.call(ws, event);
      };
      return ws;
    };
    window.WebSocket.CONNECTING = 0;
    window.WebSocket.OPEN = 1;
    window.WebSocket.CLOSING = 2;
    window.WebSocket.CLOSED = 3;
  });

  try {
    // 1. Register
    console.log('1. Registering new user...');
    await page.goto(BASE_URL + '/register');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);

    // Fill registration form
    const inputs = page.locator('input');
    await inputs.nth(0).fill('Test User');
    await inputs.nth(1).fill(TEST_EMAIL);
    await inputs.nth(2).fill(TEST_PASSWORD);
    await inputs.nth(3).fill(TEST_PASSWORD);

    await page.click('button[type="submit"]');
    await page.waitForTimeout(2000);

    if (!page.url().includes('/chat')) {
      console.log('   ⚠️ Not on chat page after registration, checking...');
      console.log('   URL:', page.url());
      await page.waitForTimeout(2000);
    }
    console.log('   ✓ Registered and logged in');

    // 2. Check if we need to create a room
    console.log('2. Checking/creating room...');
    await page.goto(BASE_URL + '/rooms');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000); // Wait for Vue to fully mount

    // Look for create room button or existing rooms
    let createRoomBtn = page.locator('button:has-text("创建房间"), button:has-text("Create Room")');
    const roomLinks = page.locator('a[href*="/chat/"]');
    let roomCount = await roomLinks.count();
    console.log(`   Found ${roomCount} existing rooms`);

    if (roomCount === 0) {
      // Create a new room
      if (await createRoomBtn.count() > 0) {
        await createRoomBtn.first().click();
        await page.waitForTimeout(500);
        // Fill room name
        const roomNameInput = page.locator('input[placeholder*="房间"], input[placeholder*="room"], input').first();
        await roomNameInput.fill('Test Room');
        await page.waitForTimeout(500);
        // Submit
        const submitBtn = page.locator('button[type="submit"]');
        await submitBtn.click();
        await page.waitForTimeout(2000);
        console.log('   ✓ Room created');
      }
    } else {
      console.log('   ✓ Using existing room');
    }

    // 3. Enter the chat room
    console.log('3. Entering chat room...');
    await page.goto(BASE_URL + '/rooms');
    await page.waitForTimeout(2000);

    // Find the "进入讨论" button/link
    const enterBtn = page.locator('button:has-text("进入讨论"), a:has-text("进入讨论")');
    const enterCount = await enterBtn.count();
    console.log(`   Found ${enterCount} "进入讨论" buttons`);

    if (enterCount > 0) {
      await enterBtn.first().click();
      await page.waitForTimeout(2000);
      console.log('   ✓ Entered chat room, URL:', page.url());
    } else {
      // Try clicking on the room card directly
      const roomCard = page.locator('[class*="room"], [class*="card"]').first();
      if (await roomCard.count() > 0) {
        await roomCard.click();
        await page.waitForTimeout(2000);
        console.log('   ✓ Clicked room card, URL:', page.url());
      } else {
        console.log('   ⚠️ Could not enter room');
        const pageText = await page.locator('body').innerText().catch(() => '');
        console.log('   Page text preview:', pageText.substring(0, 500));
        return;
      }
    }

    // 4. Open character panel and add a character
    console.log('4. Adding character to room...');
    const addCharacterBtn = page.locator('button:has-text("添加角色"), button:has-text("添加思想家")');
    const addBtnCount = await addCharacterBtn.count();
    console.log(`   Found ${addBtnCount} add character buttons`);

    if (addBtnCount > 0) {
      await addCharacterBtn.first().click();
      await page.waitForTimeout(1500);

      // Now find the create button INSIDE the panel (not the sidebar button)
      // The panel has class "fixed inset-y-0 right-0 w-80"
      const createBtn = page.locator('.fixed.inset-y-0.right-0 button:has-text("创建")');
      const createBtnCount = await createBtn.count();
      console.log(`   Found ${createBtnCount} create buttons in panel`);

      // Enter character name in the panel input
      const nameInput = page.locator('.fixed.inset-y-0.right-0 input[placeholder*="角色名称"]');
      await nameInput.fill('梅西');
      await page.waitForTimeout(500);

      // Click generate prompt button
      const generateBtn = page.locator('.fixed.inset-y-0.right-0 button:has-text("AI 生成提示词")');
      const genBtnCount = await generateBtn.count();
      console.log(`   Found ${genBtnCount} generate buttons in panel`);

      if (genBtnCount > 0) {
        await generateBtn.first().click();
        console.log('   Waiting for prompt generation...');
        await page.waitForTimeout(10000);
        console.log('   ✓ Prompt generated');
      }

      // Click the create button in the panel footer
      if (createBtnCount > 0) {
        await createBtn.first().click({ force: true });
        await page.waitForTimeout(3000);
        console.log('   ✓ Character added to room');
      } else {
        console.log('   ⚠️ Create button not found in panel');
      }
    }

    // 5. Send a test message
    console.log('5. Sending test message...');
    const messageInput = page.locator('input[placeholder*="发送"], input[placeholder*="message"], textarea');
    if (await messageInput.count() > 0) {
      await messageInput.first().fill('你好，介绍一下你自己');
      await messageInput.first().press('Enter');
      console.log('   ✓ Message sent');

      // Wait for AI response
      console.log('6. Waiting for AI response (30s)...');
      await page.waitForTimeout(30000);

      // Check for messages in the chat
      const messageElements = page.locator('[class*="message"], [class*="chat"]');
      const msgCount = await messageElements.count();
      console.log(`   Found ${msgCount} message elements`);

      // Get all text content
      const pageText = await page.locator('main').innerText().catch(() => '');
      console.log('\n========== PAGE CONTENT ==========');
      console.log(pageText.substring(0, 2000));
      console.log('================================\n');
    } else {
      console.log('   ⚠️ No message input found');
    }

    // 6. Report WebSocket messages
    const pageWsMessages = await page.evaluate(() => window.__wsMessages || []);
    const allWsMessages = [...wsMessages, ...pageWsMessages];
    if (allWsMessages.length > 0) {
      console.log('\n========== WEBSOCKET MESSAGES ==========');
      for (const msg of allWsMessages.slice(0, 20)) {
        console.log(msg.substring(0, 200));
      }
      console.log('=========================================\n');
    } else {
      console.log('\n⚠️ No WebSocket messages captured\n');
    }

    // 7. Report console errors
    if (consoleErrors.length > 0) {
      console.log('\n========== CONSOLE ERRORS ==========');
      for (const err of consoleErrors) {
        console.log(err);
      }
      console.log('======================================\n');
    }

  } catch (err) {
    console.error('❌ Test failed:', err.message);
    console.error(err.stack);
  } finally {
    await browser.close();
  }
}

test();

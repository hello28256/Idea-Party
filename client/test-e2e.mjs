import { chromium } from 'playwright';

async function runTests() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();

  let errors = [];

  console.log('=== E2E Test: Full Flow ===\n');

  try {
    // 1. Register user via API
    console.log('1. Register user...');
    const timestamp = Date.now();
    const email = `test${timestamp}@e2e.com`;

    const regResp = await page.request.post('http://localhost:8080/api/auth/register', {
      data: { username: `user${timestamp}`, email, password: 'pass123', name: 'Test User' }
    });

    if (!regResp.ok()) {
      errors.push(`Registration failed: ${await regResp.text()}`);
      console.log('   ⚠ Registration via API failed');
    } else {
      const regData = await regResp.json();
      console.log(`   ✓ User registered: ${regData.user?.email}`);
    }

    // 2. Login via UI
    console.log('2. Login via UI...');
    await page.goto('http://localhost:5173/login');
    await page.fill('input[type="email"]', email);
    await page.fill('input[type="password"]', 'pass123');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/rooms', { timeout: 10000 });
    console.log('   ✓ Logged in');

    // 3. Create room
    console.log('3. Create room...');
    await page.click('text=创建房间');
    await page.waitForSelector('input[placeholder*="例如"]', { timeout: 5000 });
    await page.fill('input[placeholder*="例如"]', `Test Room ${timestamp}`);
    await page.click('.fixed button:has-text("创建")');
    await page.waitForURL('**/chat/**', { timeout: 10000 });
    console.log('   ✓ Room created');

    // 4. Try to add character via API directly
    console.log('4. Add character via API...');
    const token = await page.evaluate(() => localStorage.getItem('accessToken'));

    const charResp = await page.request.post('http://localhost:8080/api/characters', {
      headers: { Authorization: `Bearer ${token}` },
      data: { name: 'Test Character', description: 'A test character' }
    });

    if (charResp.ok()) {
      const charData = await charResp.json();
      console.log(`   ✓ Character created via API: ${charData.name}`);
    } else {
      const errText = await charResp.text();
      console.log(`   ⚠ Character creation failed: ${errText}`);
      errors.push(`Character API failed: ${errText}`);
    }

    // 5. Reload page and check UI state
    console.log('5. Reload and check UI...');
    await page.reload();
    await page.waitForTimeout(2000);

    // Check if character appears in sidebar
    const sidebarContent = await page.textContent('aside, [class*="sidebar"]').catch(() => '');
    if (sidebarContent.includes('Test Character')) {
      console.log('   ✓ Character visible in sidebar');
    } else {
      console.log('   ⚠ Character not visible in sidebar');
    }

    // 6. Take final screenshot
    await page.screenshot({ path: 'test-final.png' });
    console.log('\n   Screenshot: test-final.png');

    // Summary
    console.log('\n=== Results ===');
    console.log(`Errors: ${errors.length}`);
    errors.forEach(e => console.log(`  - ${e}`));

  } catch (e) {
    console.error('\n❌ Test error:', e.message);
    await page.screenshot({ path: 'test-error.png' });
    errors.push(e.message);
  } finally {
    await browser.close();
  }

  process.exit(errors.length > 0 ? 1 : 0);
}

runTests();

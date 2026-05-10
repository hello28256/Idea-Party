import { chromium } from 'playwright';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();
  
  // Enable console log capture
  const consoleLogs = [];
  page.on('console', msg => consoleLogs.push(`[${msg.type()}] ${msg.text()}`));
  
  // Listen for network requests
  page.on('request', request => {
    if (request.url().includes('/api/')) {
      console.log(`[REQUEST] ${request.method()} ${request.url()}`);
    }
  });
  
  page.on('response', response => {
    if (response.url().includes('/api/')) {
      console.log(`[RESPONSE] ${response.status()} ${response.url()}`);
    }
  });

  try {
    console.log('=== Navigating to login page ===');
    await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle', timeout: 30000 });
    
    console.log('=== Filling login form ===');
    await page.fill('input[type="email"]', 'final@example.com');
    await page.fill('input[type="password"]', 'password123');
    
    console.log('=== Clicking login button ===');
    await page.click('button[type="submit"]');
    
    // Wait for navigation to rooms page
    console.log('=== Waiting for rooms page ===');
    await page.waitForURL('**/rooms', { timeout: 10000 });
    console.log('Successfully navigated to rooms page!');
    
    // Check if create room button exists
    console.log('=== Looking for create room button ===');
    const createBtn = await page.locator('button:has-text("创建房间"), button:has-text("创建第一个聊天室")').first();
    const btnVisible = await createBtn.isVisible({ timeout: 5000 }).catch(() => false);
    console.log(`Create button visible: ${btnVisible}`);
    
    if (btnVisible) {
      await createBtn.click();
      await page.waitForTimeout(1000);
      
      // Fill room name
      const nameInput = await page.locator('input[placeholder*="房间名称"], input[placeholder*="聊天室名称"]').first();
      if (await nameInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        console.log('=== Filling room name ===');
        await nameInput.fill('Test Room from Playwright');
        
        // Submit
        const submitBtn = await page.locator('button[type="submit"]:has-text("创建")').first();
        if (await submitBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
          console.log('=== Clicking submit ===');
          await submitBtn.click();
          await page.waitForTimeout(3000);
        }
      }
    }
    
    console.log('\n=== Console logs ===');
    consoleLogs.forEach(log => console.log(log));
    
    console.log('\n=== Page URL ===', page.url());
    
    // Check for error messages
    const errorText = await page.locator('text=/错误|失败|403|500|error/i').first().textContent({ timeout: 3000 }).catch(() => null);
    if (errorText) {
      console.log('\n!!! ERROR FOUND:', errorText);
    }
    
  } catch (e) {
    console.error('Test error:', e.message);
    console.log('\n=== Console logs (partial) ===');
    consoleLogs.slice(-10).forEach(log => console.log(log));
  } finally {
    await browser.close();
  }
})();

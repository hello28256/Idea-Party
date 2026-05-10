import { chromium } from 'playwright';

const BASE_URL = 'http://localhost:5174';
const TEST_EMAIL = `playwright_${Date.now()}@test.com`;
const TEST_PASSWORD = 'test123456';

async function test() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();

  try {
    // 1. Register
    console.log('1. Registering new user...');
    await page.goto(BASE_URL + '/register');
    await page.waitForLoadState('networkidle');

    await page.fill('input[type="email"]', TEST_EMAIL);
    await page.fill('input[type="password"]', TEST_PASSWORD);
    await page.click('button[type="submit"]');
    await page.waitForURL('**/chat**', { timeout: 10000 });
    console.log('   ✓ Registered and logged in');

    // 2. Open character creation panel
    console.log('2. Opening character creation panel...');
    await page.click('button:has-text("添加角色"), button:has-text("创建角色"), [data-testid="add-character"]');
    await page.waitForTimeout(1000);
    console.log('   ✓ Panel opened');

    // 3. Enter character name "梅西"
    console.log('3. Entering character name "梅西"...');
    const nameInput = page.locator('input[placeholder*="角色名称"], input[placeholder*="name"]');
    await nameInput.fill('梅西');
    console.log('   ✓ Name entered');

    // 4. Click generate prompt button
    console.log('4. Clicking generate prompt button...');
    const generateBtn = page.locator('button:has-text("AI 生成提示词"), button:has-text("生成")');
    await generateBtn.click();
    console.log('   ✓ Generate button clicked');

    // 5. Wait for result and capture prompt
    console.log('5. Waiting for generated prompt...');
    await page.waitForTimeout(8000); // Wait for AI generation

    // Get prompt textarea content
    const promptTextarea = page.locator('textarea').last();
    const generatedPrompt = await promptTextarea.inputValue();

    console.log('\n========== GENERATED PROMPT ==========\n');
    console.log(generatedPrompt);
    console.log('\n========================================\n');

    // Check if prompt has meaningful content (not the template)
    if (generatedPrompt.includes('A complex individual') || generatedPrompt.length < 100) {
      console.log('❌ PROBLEM: Prompt is too short or uses template text');
    } else if (generatedPrompt.includes('足球') || generatedPrompt.includes('Messi') || generatedPrompt.includes('Barcelona') || generatedPrompt.includes('football')) {
      console.log('✅ SUCCESS: Prompt contains Messi-related content');
    } else {
      console.log('⚠️  UNCERTAIN: Prompt generated but content unclear');
    }

  } catch (err) {
    console.error('❌ Test failed:', err.message);
  } finally {
    await browser.close();
  }
}

test();

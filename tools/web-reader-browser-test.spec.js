const { test, expect } = require('@playwright/test');

const baseUrl = process.env.UQUREADER_WEB_URL || 'http://localhost:8090';
const chromePath = process.env.CHROME_PATH || 'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe';

test.use({
  launchOptions: {
    executablePath: chromePath
  }
});

test('web reader auth, paging, tts endpoint and stats flow', async ({ page }) => {
  const network = [];
  const consoleMessages = [];
  page.on('response', async (response) => {
    const url = response.url();
    if (url.includes('/api/')) {
      network.push({ method: response.request().method(), url, status: response.status() });
    }
  });
  page.on('console', (message) => consoleMessages.push(`${message.type()}: ${message.text()}`));
  page.on('pageerror', (error) => consoleMessages.push(`pageerror: ${error.message}`));
  await page.addInitScript(() => {
    window.__speechSpeakCount = 0;
    const original = window.speechSynthesis && window.speechSynthesis.speak;
    if (original) {
      window.speechSynthesis.speak = function patchedSpeak(...args) {
        window.__speechSpeakCount += 1;
        return original.apply(this, args);
      };
    }
  });

  const username = `pw_${Date.now()}`;
  await page.goto(`${baseUrl}/reader`, { waitUntil: 'domcontentloaded' });
  await page.fill('#username', username);
  await page.fill('#password', 'secret123');
  await page.click('#registerButton');
  await expect(page.locator('#readerPanel')).not.toHaveClass(/hidden/, { timeout: 10_000 });
  await expect(page.locator('#workSelect option').first()).toBeAttached({ timeout: 10_000 });

  const workCount = await page.locator('#workSelect option').count();
  expect(workCount).toBeGreaterThan(0);
  await expect(page.locator('#page')).not.toBeEmpty();
  await expect(page.locator('#page .reader-paragraph').first()).toBeVisible();
  expect(await page.locator('#page .reader-paragraph').count()).toBeGreaterThan(0);
  expect(await page.locator('#page .reader-paragraph .token').count()).toBeGreaterThan(0);
  const initialText = (await page.locator('#page').innerText()).slice(0, 120);
  await page.waitForTimeout(1200);

  const ttsStatus = await page.evaluate(async () => {
    const response = await fetch('/api/tts/status', { credentials: 'same-origin' });
    return response.json();
  });

  const audioResult = await page.evaluate(async () => {
    const response = await fetch('/api/tts/speech', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: 'Татарча җөмлә.' })
    });
    const contentType = response.headers.get('content-type') || '';
    const bytes = response.ok ? (await response.arrayBuffer()).byteLength : 0;
    const body = response.ok ? '' : await response.text();
    return { status: response.status, contentType, bytes, body };
  });
  await page.click('#speakPage');
  await page.waitForTimeout(800);

  await page.click('#nextPage');
  await page.waitForTimeout(1400);
  await page.click('#prevPage');
  await page.waitForTimeout(1200);
  await page.locator('.token').first().click();
  await expect(page.locator('#tokenSheet')).not.toHaveClass(/hidden/);
  await page.evaluate(() => window.dispatchEvent(new Event('pagehide')));
  await page.waitForTimeout(1500);

  await page.click('#statsButton');
  await expect(page.locator('#statsPanel')).not.toHaveClass(/hidden/);
  await page.waitForTimeout(1000);
  const lemmaRows = await page.locator('#statsContent .stat-row').count();
  expect(lemmaRows).toBeGreaterThan(1);
  await page.locator('#statsContent .stat-row.clickable').first().click();
  await expect(page.locator('.timeline-panel')).toBeVisible();
  const timelineDots = await page.locator('.timeline-svg circle').count();
  expect(timelineDots).toBeGreaterThan(0);
  await page.click('#featureStatsTab');
  await page.waitForTimeout(1000);
  const featureRows = await page.locator('#statsContent .stat-row').count();
  expect(featureRows).toBeGreaterThan(1);

  const authMe = await page.evaluate(async () => {
    const response = await fetch('/api/auth/me', { credentials: 'same-origin' });
    return response.json();
  });
  expect(authMe.authenticated).toBeTruthy();
  const browserSpeechSpeakCount = await page.evaluate(() => window.__speechSpeakCount || 0);
  if (!ttsStatus.configured) {
    expect(browserSpeechSpeakCount).toBe(0);
  }

  console.log(JSON.stringify({
    username,
    authenticated: authMe.authenticated,
    workCount,
    initialText,
    ttsStatus,
    audioResult,
    browserSpeechSpeakCount,
    lemmaRows,
    featureRows,
    network,
    consoleMessages
  }, null, 2));

  if (ttsStatus.configured) {
    expect(audioResult.status).toBe(200);
    expect(audioResult.contentType).toContain('audio/wav');
    expect(audioResult.bytes).toBeGreaterThan(44);
  } else {
    expect(audioResult.status).toBe(503);
  }
});

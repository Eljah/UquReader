const { test, expect } = require('@playwright/test');

const baseUrl = process.env.UQUREADER_WEB_URL || 'http://localhost:8093';
const chromePath = process.env.CHROME_PATH || 'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe';

test.use({
  launchOptions: {
    executablePath: chromePath
  }
});

test('grammar catalog is loaded from backend and rendered in reader stats', async ({ page }) => {
  const grammar = await page.request.get(`${baseUrl}/api/grammar`);
  expect(grammar.ok()).toBeTruthy();
  const catalog = await grammar.json();
  expect(catalog.pos.find(item => item.code === 'N')?.titleRu).toBe('\u0441\u0443\u0449\u0435\u0441\u0442\u0432\u0438\u0442\u0435\u043b\u044c\u043d\u043e\u0435');
  expect(catalog.features.find(item => item.code === 'PL')?.examples).toContain(
    '\u0431\u0430\u043b\u0430 - \u0431\u0430\u043b\u0430\u043b\u0430\u0440, \u0440\u0435\u0431\u0435\u043d\u043e\u043a - \u0434\u0435\u0442\u0438'
  );
  expect(catalog.features.find(item => item.code === 'ACC')?.titleRu).toBe('\u0432\u0438\u043d\u0438\u0442\u0435\u043b\u044c\u043d\u044b\u0439 \u043f\u0430\u0434\u0435\u0436');

  await page.goto(`${baseUrl}/reader`, { waitUntil: 'domcontentloaded' });
  await page.fill('#username', `grammar_${Date.now()}`);
  await page.fill('#password', 'secret123');
  await page.click('#registerButton');
  await expect(page.locator('#readerPanel')).not.toHaveClass(/hidden/, { timeout: 10_000 });
  await expect(page.locator('#page .token').first()).toBeVisible({ timeout: 10_000 });

  await page.locator('#page .token').first().click();
  await expect(page.locator('#tokenPos')).toContainText('N');
  await expect(page.locator('#tokenPos')).toContainText('\u0441\u0443\u0449\u0435\u0441\u0442\u0432\u0438\u0442\u0435\u043b\u044c\u043d\u043e\u0435');
  await expect(page.locator('#tokenFeatures')).toContainText('Sg');
  await expect(page.locator('#tokenFeatures')).toContainText('\u0435\u0434\u0438\u043d\u0441\u0442\u0432\u0435\u043d\u043d\u043e\u0435 \u0447\u0438\u0441\u043b\u043e');
  await expect(page.locator('#tokenFeatures')).toContainText('Nom');
  await expect(page.locator('#tokenFeatures')).toContainText('\u0438\u043c\u0435\u043d\u0438\u0442\u0435\u043b\u044c\u043d\u044b\u0439 \u043f\u0430\u0434\u0435\u0436');

  const formatted = await page.evaluate(() => ({
    poss3: window.formatFeature('POSS_3'),
    dir: window.formatFeature('DIR'),
    breakdown: window.formatBreakdown('\u044f\u0448\u0435\u043d\u04d9', ['\u044f\u0448\u044c', '\u0435', '\u043d\u04d9'])
  }));
  expect(formatted.poss3).toBe('POSS_3 - \u043f\u0440\u0438\u0442\u044f\u0436\u0430\u0442\u0435\u043b\u044c\u043d\u043e\u0441\u0442\u044c 3-\u0433\u043e \u043b\u0438\u0446\u0430, \u043d\u0430\u043f\u0440\u0438\u043c\u0435\u0440 "\u0431\u0430\u043b\u0430 - \u0431\u0430\u043b\u0430\u0441\u044b, \u0440\u0435\u0431\u0435\u043d\u043e\u043a - \u0435\u0433\u043e \u0440\u0435\u0431\u0435\u043d\u043e\u043a"');
  expect(formatted.dir).toBe('DIR - \u043d\u0430\u043f\u0440\u0430\u0432\u0438\u0442\u0435\u043b\u044c\u043d\u044b\u0439 \u043f\u0430\u0434\u0435\u0436, \u043d\u0430\u043f\u0440\u0438\u043c\u0435\u0440 "\u0431\u0430\u043b\u0430 - \u0431\u0430\u043b\u0430\u0433\u0430, \u0440\u0435\u0431\u0435\u043d\u043e\u043a - \u0440\u0435\u0431\u0435\u043d\u043a\u0443"');
  expect(formatted.poss3).not.toContain('\u0448\u0435');
  expect(formatted.breakdown).toBe('\u044f\u0448\u0435\u043d\u04d9: \u044f\u0448\u044c-\u0435-\u043d\u04d9');

  await page.evaluate(() => window.dispatchEvent(new Event('pagehide')));
  await page.waitForTimeout(1200);
  await page.click('#statsButton');
  await page.click('#featureStatsTab');
  await expect(page.locator('#statsContent')).toContainText('\u0441\u0443\u0449\u0435\u0441\u0442\u0432\u0438\u0442\u0435\u043b\u044c\u043d\u043e\u0435');
  await expect(page.locator('#statsContent')).toContainText('\u0435\u0434\u0438\u043d\u0441\u0442\u0432\u0435\u043d\u043d\u043e\u0435 \u0447\u0438\u0441\u043b\u043e');
  await expect(page.locator('#statsContent')).toContainText('\u0438\u043c\u0435\u043d\u0438\u0442\u0435\u043b\u044c\u043d\u044b\u0439 \u043f\u0430\u0434\u0435\u0436');
});

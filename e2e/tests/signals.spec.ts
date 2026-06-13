import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { apiLogin, loginThroughForm, publishE2eStrategy, resetLoginLimiter } from './helpers';

test.describe('live signals — the MVP statement (C-2.28)', () => {
  test.beforeEach(resetLoginLimiter); // fresh A.2.6 window per journey
  test('a published strategy produces a live signal visible with its breakdown', async ({
    page,
    request,
  }) => {
    // API fixture: publish the deterministic momentum strategy (warm history
    // through the cache-first candle surface makes the next 1m bar scoreable)
    await apiLogin(request);
    await publishE2eStrategy(request);

    await loginThroughForm(page);
    await page.goto('/signals');

    // mock tick -> candle -> engine -> STOMP -> browser; first 1m close lands within ~75s
    const firstRow = page.locator('p-table tbody tr').first();
    await expect(firstRow).toContainText('RELIANCE', { timeout: 180_000 });
    await expect(firstRow).toContainText('ENTRY');

    // click through to the reasoning breakdown (the frozen C-2.6 contract)
    await firstRow.click();
    const panel = page.locator('ay-reasoning-breakdown');
    await expect(panel).toBeVisible();
    await expect(panel).toContainText('Composite vs threshold');
    await expect(panel).toContainText('rsi_1m');
    await expect(panel).toContainText('REQUIRED');
    await expect(panel).toContainText('close > 1'); // gate leaf with operand values
    await expect(panel).toContainText('weight denominator');
  });

  test('axe: the signals page has no detectable violations', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/signals');
    await expect(page.locator('p-table')).toBeVisible();
    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});

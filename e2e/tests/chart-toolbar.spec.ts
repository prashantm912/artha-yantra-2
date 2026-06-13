import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { loginThroughForm, resetLoginLimiter } from './helpers';

test.describe('chart toolbar, overlays & persistence (Phase 40C)', () => {
  test.beforeEach(resetLoginLimiter);

  test('interval choice persists across a reload (first-party chart-state persistence)', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/charts');
    await expect(page.locator('ay-chart-toolbar')).toBeVisible();

    // pick 5m and confirm it persists to localStorage, then survives a reload
    await page.getByRole('button', { name: '5m', exact: true }).click();
    await expect
      .poll(async () => (await page.evaluate(() => localStorage.getItem('ay.chart')))?.includes('"5m"'))
      .toBe(true);

    await page.reload();
    await expect(page.getByRole('button', { name: '5m', exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  test('the "View as table" toggle exposes the accessible OHLCV table', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/charts');
    await page.getByRole('button', { name: 'View as table' }).click();
    await expect(page.getByRole('columnheader', { name: 'Open' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Close' })).toBeVisible();
  });

  test('axe: the charts page (with the accessible table toggle) has no violations', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/charts');
    await expect(page.locator('ay-chart-toolbar')).toBeVisible();
    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});

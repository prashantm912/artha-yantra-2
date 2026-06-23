import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { loginThroughForm, resetLoginLimiter } from './helpers';

test.describe('dashboard — the daily-driver surface (Phase 35)', () => {
  test.beforeEach(resetLoginLimiter);

  test('market overview shows a live tick price and the jobs widget renders', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/dashboard');

    // the widget grid is up
    await expect(page.locator('ay-market-overview-widget')).toBeVisible();
    await expect(page.locator('ay-jobs-widget')).toBeVisible();

    // a live tick reaches the NIFTY 50 card and renders as a 2-dp price (conflation → signal → DOM)
    await expect(page.locator('ay-market-overview-widget')).toContainText(/\d+\.\d{2}/, {
      timeout: 60_000,
    });
  });

  test('the jobs page table renders with live-progress columns', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/backtests/jobs');
    await expect(page.locator('p-table')).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Progress' })).toBeVisible();
  });

  test('axe: the dashboard has no detectable violations', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/dashboard');
    await expect(page.locator('ay-market-overview-widget')).toBeVisible();
    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});

import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { apiLogin, loginThroughForm, publishE2eStrategy, resetLoginLimiter } from './helpers';

test.describe('backtest runner / results / compare (Phase 38)', () => {
  test.beforeEach(resetLoginLimiter);

  test('runner submits a backtest that appears in the jobs monitor', async ({ page, request }) => {
    await apiLogin(request);
    await publishE2eStrategy(request);

    await loginThroughForm(page);
    await page.goto('/backtests/run');

    // pick the strategy and submit a backtest
    await page.locator('p-select').first().click();
    await page.locator('.p-select-option').first().click();
    await page.getByRole('button', { name: 'Run backtest' }).click();

    // lands on the jobs monitor with a job row + progress column
    await expect(page).toHaveURL(/\/backtests\/jobs/, { timeout: 15_000 });
    await expect(page.getByRole('columnheader', { name: 'Progress' })).toBeVisible();
    await expect(page.locator('p-table tbody tr').first()).toBeVisible({ timeout: 30_000 });
    // NOTE: a full backtest→results→compare run needs benchmark history seeded (see the guide);
    // that end-to-end is walked at the stage-end manual pass.
  });

  test('axe: the backtest runner has no detectable violations', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/backtests/run');
    await expect(page.locator('p-tabs')).toBeVisible();
    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});

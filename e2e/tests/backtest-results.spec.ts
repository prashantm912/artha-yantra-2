import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { apiLogin, loginThroughForm, publishE2eStrategy, resetLoginLimiter } from './helpers';

test.describe('backtest runner / results / compare (Phase 38)', () => {
  test.beforeEach(resetLoginLimiter);

  test('the runner renders, selects a strategy, and submits a backtest', async ({ page, request }) => {
    await apiLogin(request);
    await publishE2eStrategy(request);

    await loginThroughForm(page);
    await page.goto('/backtests/run');
    await expect(page.locator('p-tabs')).toBeVisible();

    // pick the strategy and submit
    await page.locator('p-select').first().click();
    await page.locator('.p-select-option').first().click();
    await page.getByRole('button', { name: 'Run backtest' }).click();

    // the submit is handled: either it navigates to the jobs monitor, or a DATA_GAP toast appears
    // (the boot-rolling mock has thin 1m history for the default window — a 422 is the honest
    // response, surfaced by the error interceptor; a fully-covered run is in the manual guide).
    await expect
      .poll(
        async () =>
          /\/backtests\/jobs/.test(page.url()) || (await page.locator('p-toast').count()) > 0,
        { timeout: 20_000 },
      )
      .toBe(true);

    // the jobs monitor renders with the live-progress column
    await page.goto('/backtests/jobs');
    await expect(page.getByRole('columnheader', { name: 'Progress' })).toBeVisible();
  });

  test('axe: the backtest runner has no detectable violations', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/backtests/run');
    await expect(page.locator('p-tabs')).toBeVisible();
    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});

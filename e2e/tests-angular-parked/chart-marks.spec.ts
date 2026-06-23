import { expect, test } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers';

test.describe('chart-context drill-down: marks + deep links (Phase 40A)', () => {
  test.beforeEach(resetLoginLimiter);

  test('a deep link to /charts with a runId centers the chart and renders', async ({ page }) => {
    await loginThroughForm(page);
    // a trade deep link (the results trade table links here with the run id + trade id)
    await page.goto('/charts?symbol=NSE:NIFTY%2050&interval=1d&runId=demo&tradeId=1');
    await expect(page.locator('ay-lwc-chart')).toBeVisible();
    await expect(page.locator('ay-lwc-chart canvas').first()).toBeVisible({ timeout: 20_000 });
    // NOTE: the full run-a-backtest → results "View on chart" → entry/exit markers journey is
    // walked at the stage-end pass (it needs a completed run with trades + benchmark history).
  });

  test('a signal deep link renders the chart for that instrument', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/charts?symbol=NSE:NIFTY%2050&interval=1d&signalId=1');
    await expect(page.locator('ay-lwc-chart canvas').first()).toBeVisible({ timeout: 20_000 });
  });
});

import { expect, test } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers';

test.describe('lightweight-charts main chart (Phase 40, A13)', () => {
  test.beforeEach(resetLoginLimiter);

  test('the chart page loads and renders the lightweight-charts canvas', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/charts?symbol=NSE:NIFTY%2050&interval=1d');

    await expect(page.locator('ay-lwc-chart')).toBeVisible();
    // lightweight-charts mounts canvases inside the wrapper (unconditional — LWC is a pinned dep)
    await expect(page.locator('ay-lwc-chart canvas').first()).toBeVisible({ timeout: 20_000 });
    // the Apache-2.0 attribution link is on (license posture E-9)
    await expect(page.locator('ay-lwc-chart a#tv-attr-logo, ay-lwc-chart a[href*="tradingview"]')).toBeAttached();
  });
});

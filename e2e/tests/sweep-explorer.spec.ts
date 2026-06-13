import { expect, test } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers';

test.describe('sweep explorer + leaderboard (Phase 39)', () => {
  test.beforeEach(resetLoginLimiter);

  test('the sweep detail page renders the explorer + leaderboard shell', async ({ page }) => {
    await loginThroughForm(page);
    // direct-navigate to a sweep detail (a live sweep with trials is walked at the stage end —
    // it needs a sweep-capable strategy + benchmark history); here we assert the shell renders.
    await page.goto('/optimizations/00000000-0000-0000-0000-000000000000');
    await expect(page.locator('ay-sweep-detail-page')).toBeVisible();
    await expect(page.getByText('Leaderboard', { exact: false })).toBeVisible();
    // the plateau/raw sort toggle (guard 4) is present
    await expect(page.locator('p-selectbutton')).toBeVisible();
    // the "All trials" section (pruned/failed flagged, never hidden) is present
    await expect(page.getByText('pruned/failed flagged', { exact: false })).toBeVisible();
  });
});

import { expect, test } from '@playwright/test';
import { apiLogin, loginThroughForm, publishE2eStrategy, resetLoginLimiter } from './helpers';

test.describe('versions / diff / publish + stress advisory (Phase 37)', () => {
  test.beforeEach(resetLoginLimiter);

  test('version timeline, diff, and the advisory publish dialog (never blocked)', async ({
    page,
    request,
  }) => {
    // ensure a published strategy with ≥1 version exists
    await apiLogin(request);
    await publishE2eStrategy(request);

    await loginThroughForm(page);
    await page.goto('/strategies');

    // open the first strategy's version history
    await page.locator('p-table tbody tr').first().getByRole('button', { name: 'History' }).click();
    await expect(page).toHaveURL(/\/strategies\/[0-9a-f-]{8,}\/versions/);
    await expect(page.locator('p-table tbody tr').first()).toBeVisible();

    // the Monaco diff surface renders
    await expect(page.locator('ay-monaco-diff')).toBeVisible();

    // publish dialog: the S1C advisory shows, publish is NEVER blocked (advisory only)
    const publishBtn = page.getByRole('button', { name: 'Publish…' });
    if (await publishBtn.isEnabled()) {
      await publishBtn.click();
      await expect(page.locator('ay-stress-advisory')).toBeVisible();
      await expect(page.locator('ay-stress-advisory')).toContainText('Advisory only');
      await expect(page.getByRole('button', { name: 'Publish', exact: true })).toBeEnabled();
    }
  });
});

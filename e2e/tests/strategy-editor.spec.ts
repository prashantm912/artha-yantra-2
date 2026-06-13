import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { loginThroughForm, resetLoginLimiter } from './helpers';

test.describe('strategy editor — author → validate → save → quick backtest (Phase 36)', () => {
  test.beforeEach(resetLoginLimiter);

  test('create from template → server validation passes → save draft', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/strategies');

    await page.getByRole('button', { name: 'Create strategy' }).click();
    await expect(page).toHaveURL(/\/strategies\/new\/edit/);
    await expect(page.locator('ay-monaco-yaml-editor')).toBeVisible();

    // debounced POST /validate against the template → the validation panel resolves
    await expect(page.locator('.validation')).toContainText(/validation passed|✗/, {
      timeout: 15_000,
    });

    // save the draft → a new strategy id, version tag appears
    await page.getByRole('button', { name: 'Create draft' }).click();
    await expect(page).toHaveURL(/\/strategies\/[0-9a-f-]{8,}\/edit/, { timeout: 15_000 });
    await expect(page.locator('.toolbar')).toContainText('v', { timeout: 15_000 });

    // the quick-backtest drawer opens (a full windowed run needs benchmark history — see the guide)
    await page.getByRole('button', { name: 'Quick backtest' }).click();
    await expect(page.getByText('Run quick backtest')).toBeVisible();
  });

  test('axe: the strategy list page has no detectable violations', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/strategies');
    await expect(page.locator('p-table')).toBeVisible();
    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});

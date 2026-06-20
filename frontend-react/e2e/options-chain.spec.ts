import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

// Pre-authenticated via the shared storageState (global-setup). Verifies the hybrid shell + the
// FilterBar control bar + the OI header on the Options Chain anchor page (§20.6 corrected mapping),
// at both viewports, with no axe violations.
test('Options Chain renders the control bar + header, no axe violations', async ({ page }) => {
  await page.goto('/options/options-chain');

  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('button', { name: 'All Menu ▾' })).toBeVisible();
  await expect(page.getByLabel('Theme')).toBeVisible();

  await expect(page.getByLabel('Underlying')).toBeVisible();
  await expect(page.getByLabel('Interval')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Toggle live/history mode' })).toBeVisible();

  // Regression (the empty-options bug): underlying options must carry real values + default non-empty.
  const underlying = page.getByLabel('Underlying');
  const values = await underlying
    .locator('option')
    .evaluateAll((opts) => opts.map((o) => (o as HTMLOptionElement).value).filter(Boolean));
  expect(values.length).toBeGreaterThan(5);
  expect(await underlying.inputValue()).not.toBe('');

  // Cascade: selecting a different underlying reloads the expiry list (not disabled/empty).
  await underlying.selectOption(values[0]);
  await expect(page.getByLabel('Expiry')).toBeEnabled();

  await expect(page.getByText(/OI bias/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Options chain' })).toBeAttached();

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

// Pre-authenticated via the shared storageState (global-setup). Verifies the hybrid shell + the
// FilterBar control bar + the OI header on the anchor page, at both viewports, with no axe violations.
test('OI Analysis anchor renders the control bar + header, no axe violations', async ({ page }) => {
  await page.goto('/options/oi-analysis');

  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('button', { name: 'All Menu ▾' })).toBeVisible();
  await expect(page.getByLabel('Theme')).toBeVisible();

  await expect(page.getByLabel('Underlying')).toBeVisible();
  await expect(page.getByLabel('Interval')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Toggle live/history mode' })).toBeVisible();

  await expect(page.getByText(/OI bias/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Options OI analysis' })).toBeAttached();

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

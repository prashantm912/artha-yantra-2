import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { loginThroughForm } from './helpers.ts';

test('OI Analysis anchor renders the control bar + header, no axe violations', async ({ page }) => {
  await loginThroughForm(page);
  await page.goto('/options/oi-analysis');

  // The hybrid shell chrome.
  await expect(page.getByRole('button', { name: 'All Menu ▾' })).toBeVisible();
  await expect(page.getByLabel('Theme')).toBeVisible();

  // The FilterBar control bar.
  await expect(page.getByLabel('Underlying')).toBeVisible();
  await expect(page.getByLabel('Interval')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Toggle live/history mode' })).toBeVisible();

  // The OI-bias line + sr-only page heading.
  await expect(page.getByText(/OI bias/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Options OI analysis' })).toBeAttached();

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

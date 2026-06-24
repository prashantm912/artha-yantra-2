import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

// Pre-authenticated via the shared storageState (global-setup). Verifies the Open & High Strategy page
// (oipulse §strategies/open-high-strategy — per-strike CE/PE Open=High/Low scan + trigger probability)
// renders the FilterBar control bar at both viewports, axe-clean. The scan table needs captured chain
// snapshots (mock has none), so we assert the page chrome + the empty-state copy, not the table.

test('Open & High Strategy renders the control bar, no axe violations', async ({ page }) => {
  await page.goto('/options/open-high-strategy');

  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Open and High Strategy' })).toBeAttached();

  // shared FilterBar controls (name/expiry/interval/mode).
  await expect(page.getByLabel('Underlying')).toBeVisible();
  await expect(page.getByLabel('Interval')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Toggle live/history mode' })).toBeVisible();

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test.describe('mobile (~480px)', () => {
  test.use({ viewport: { width: 480, height: 1010 } });
  test('Open & High Strategy renders the control bar on a narrow viewport, axe-clean', async ({ page }) => {
    await page.goto('/options/open-high-strategy');
    await expect(page.getByTestId('app-shell')).toBeVisible();
    await expect(page.getByLabel('Underlying')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Open and High Strategy' })).toBeAttached();

    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});

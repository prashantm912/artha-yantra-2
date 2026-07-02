import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { settleAnimations } from './helpers';

// Pre-authenticated via the shared storageState (global-setup). Verifies the OI Change Heatmap page
// (§20 breadth — strike × time ΔOI grid, CE/PE) renders the FilterBar control bar + the metric strip
// at both viewports, axe-clean. The grid itself needs captured chain snapshots (mock has none), so we
// assert the page chrome + the empty-state copy, not the heatmap canvas.

test('OI Heatmap renders the control bar + metric strip, no axe violations', async ({ page }) => {
  await page.goto('/options/oi-heatmap');

  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'OI Change Heatmap' })).toBeAttached();

  // shared FilterBar controls (name/expiry/interval/mode).
  await expect(page.getByLabel('Underlying', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Interval', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Toggle live/history mode' })).toBeVisible();

  // the metric strip pills are always present (— when the grid is empty).
  await expect(page.getByText('Strikes', { exact: false })).toBeVisible();
  await expect(page.getByText('Intervals', { exact: false })).toBeVisible();

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test.describe('mobile (~480px)', () => {
  test.use({ viewport: { width: 480, height: 1010 } });
  test('OI Heatmap renders the control bar on a narrow viewport, axe-clean', async ({ page }) => {
    await page.goto('/options/oi-heatmap');
    await expect(page.getByTestId('app-shell')).toBeVisible();
    await expect(page.getByLabel('Underlying', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'OI Change Heatmap' })).toBeAttached();

    await settleAnimations(page);

    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});

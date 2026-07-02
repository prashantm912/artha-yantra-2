import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { settleAnimations } from './helpers';

// Pre-authenticated via the shared storageState (global-setup). Verifies the OI Expiry Strategy page
// (oipulse "Options EOD OI Analysis" — per-strike last-N-session EOD OHLC + OI tables) renders the
// FilterBar control bar at both viewports, axe-clean. The tables themselves need captured chain
// snapshots (mock has none), so we assert the page chrome + the empty-state copy, not the tables.

test('OI Expiry Strategy renders the control bar, no axe violations', async ({ page }) => {
  await page.goto('/options/oi-expiry-strategy');

  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'OI Expiry Strategy' })).toBeAttached();

  // shared FilterBar controls (name/expiry/interval/mode).
  await expect(page.getByLabel('Underlying', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Interval', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Toggle live/history mode' })).toBeVisible();

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test.describe('mobile (~480px)', () => {
  test.use({ viewport: { width: 480, height: 1010 } });
  test('OI Expiry Strategy renders the control bar on a narrow viewport, axe-clean', async ({ page }) => {
    await page.goto('/options/oi-expiry-strategy');
    await expect(page.getByTestId('app-shell')).toBeVisible();
    await expect(page.getByLabel('Underlying', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'OI Expiry Strategy' })).toBeAttached();

    await settleAnimations(page);

    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});

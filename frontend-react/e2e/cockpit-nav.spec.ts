import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

// Cockpit-parity nav journeys (React-DOM ports of the parked Angular e2e, read-only paths only — no
// publish/order mutations, so they are safe against any reused stack). Pre-authenticated via the
// shared storageState (global-setup). Each cockpit route must render its anchor control behind auth.

test('the scalper cockpit renders its three zones + the option quick-pick, no axe violations', async ({ page }) => {
  await page.goto('/scalper');
  await expect(page.getByTestId('app-shell')).toBeVisible();
  // headings (role) — the empty-state copy contains the same words, so role disambiguates
  await expect(page.getByRole('heading', { name: 'Live signals' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Paper order ticket' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Positions & P&L' })).toBeVisible();
  await expect(page.getByLabel('Instrument')).toBeVisible();
  await expect(page.getByLabel('Side')).toBeVisible();

  // the option quick-pick toggles the strike-ladder picker
  await page.getByRole('button', { name: /Option quick-pick/ }).click();
  await expect(page.getByLabel('Underlying')).toBeVisible();

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test('every cockpit route renders its anchor control behind auth', async ({ page }) => {
  // grouped so the suite stays one session (one login); each is a read-only render assertion
  const routes: { path: string; locator: () => ReturnType<typeof page.getByRole> | ReturnType<typeof page.getByLabel> }[] = [
    { path: '/dashboard', locator: () => page.getByRole('heading', { name: 'Active Signals' }) },
    { path: '/signals', locator: () => page.getByLabel('Status filter') },
    { path: '/paper', locator: () => page.getByRole('heading', { name: 'Open positions' }) },
    { path: '/journal', locator: () => page.getByLabel('Filter by tag') },
    { path: '/watchlists', locator: () => page.getByRole('tab', { name: 'Screener' }) },
    { path: '/settings', locator: () => page.getByRole('heading', { name: 'Kite Connect' }) },
    { path: '/strategies', locator: () => page.getByRole('link', { name: '+ Create' }) },
    { path: '/backtests/run', locator: () => page.getByRole('tab', { name: 'backtest' }) },
    { path: '/backtests/jobs', locator: () => page.getByRole('button', { name: /Reload/ }) },
    { path: '/charts', locator: () => page.getByLabel('Instrument') },
  ];

  for (const { path, locator } of routes) {
    await page.goto(path);
    await expect(page.getByTestId('app-shell'), `${path} shell`).toBeVisible();
    await expect(locator(), `${path} anchor`).toBeVisible({ timeout: 15_000 });
  }
});

test.describe('mobile (~480px)', () => {
  test.use({ viewport: { width: 480, height: 1010 } });
  test('the scalper cockpit stacks and still renders the ticket', async ({ page }) => {
    await page.goto('/scalper');
    await expect(page.getByTestId('app-shell')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Paper order ticket' })).toBeVisible();
    await expect(page.getByLabel('Instrument')).toBeVisible();
  });
});

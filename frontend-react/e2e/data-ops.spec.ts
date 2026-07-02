import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { settleAnimations } from './helpers';

// Data Ops operator console (wave PR-DO) — render + a11y smoke over the five /data-ops routes. All
// pages must render on the MOCK stack without live data (graceful degrade / on-demand POSTs not
// triggered): the Run / Start backfill / Download buttons POST on demand and 503 on mock, so these
// specs assert STRUCTURE only and never click them. Pre-authenticated via the shared storageState
// (global-setup). Mirrors cockpit-nav.spec.ts (role/text selectors, AxeBuilder, mobile describe).

test('/data-ops/status renders the backfill cards + quota gauge, no axe violations', async ({ page }) => {
  await page.goto('/data-ops/status');
  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Collection Status' })).toBeVisible();
  // The two backfill cards (h2s) + the quota gauge — all render even when the jobs have NEVER_RUN.
  await expect(page.getByRole('heading', { name: 'Expired backfill' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'OI backfill' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Upstox quota' })).toBeVisible();
  // Quota is "not configured" on mock (analytics token off) — proves the graceful-degrade path.
  await expect(page.getByText(/not configured/)).toBeVisible();

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test('/data-ops/coverage renders the stat pills + table-or-empty-state, no axe violations', async ({ page }) => {
  await page.goto('/data-ops/coverage');
  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Data Coverage' })).toBeVisible();
  // Grand-total stat pills (dt labels) always render. `.first()` — the same labels recur as table
  // column headers when coverage rows are populated (live stack), so the locator can match >1.
  await expect(page.getByText('Contracts', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('Candle rows', { exact: true }).first()).toBeVisible();
  // Either the empty-state copy (mock: no coverage captured) OR a populated table — both are valid.
  const empty = page.getByText('No coverage captured yet.');
  const table = page.getByRole('table', { name: 'Expired-contract coverage' });
  await expect(empty.or(table)).toBeVisible();

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test('/data-ops/collection renders the stepper + step-0 indices, no axe violations', async ({ page }) => {
  await page.goto('/data-ops/collection');
  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Collection Wizard' })).toBeVisible();
  // The Stepper (ol aria-label="Progress") + the step-0 MultiCheckboxGroup of indices.
  await expect(page.getByRole('list', { name: 'Progress' })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: 'NIFTY' })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: 'SENSEX' })).toBeVisible();
  // Do NOT submit — Start backfill POSTs on demand (503 on mock). Just assert it is present.
  await expect(page.getByRole('button', { name: 'Next' })).toBeVisible();

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test('/data-ops/query renders presets + editor + controls, no axe violations', async ({ page }) => {
  await page.goto('/data-ops/query');
  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Query / Scan console' })).toBeVisible();
  // Preset buttons (left nav) + the SqlEditor textarea + the row-limit input.
  await expect(page.getByRole('button', { name: 'Recent backfill 1m' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Contracts by expiry' })).toBeVisible();
  await expect(page.getByLabel('SQL query', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Row limit', { exact: true })).toBeVisible();
  // Run + Download CSV present — do NOT click them (read-only allowlist runs server-side; 503 on mock).
  await expect(page.getByRole('button', { name: 'Run', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Download CSV' })).toBeVisible();

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test('/data-ops/export renders the stepper + step-0 underlying picker, no axe violations', async ({ page }) => {
  await page.goto('/data-ops/export');
  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Export Wizard' })).toBeVisible();
  // The Stepper + the step-0 underlying buttons (NIFTY / SENSEX). Do NOT advance to Download.
  await expect(page.getByRole('list', { name: 'Progress' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'NIFTY' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'SENSEX' })).toBeVisible();

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test.describe('mobile (~480px)', () => {
  test.use({ viewport: { width: 480, height: 1010 } });

  test('the Data Ops routes still render their anchor heading + control at ~480px', async ({ page }) => {
    // Grouped so the suite stays one session; each is a read-only render assertion (no POSTs).
    const routes: { path: string; heading: string }[] = [
      { path: '/data-ops/status', heading: 'Collection Status' },
      { path: '/data-ops/coverage', heading: 'Data Coverage' },
      { path: '/data-ops/collection', heading: 'Collection Wizard' },
      { path: '/data-ops/query', heading: 'Query / Scan console' },
      { path: '/data-ops/export', heading: 'Export Wizard' },
    ];
    for (const { path, heading } of routes) {
      await page.goto(path);
      await expect(page.getByTestId('app-shell'), `${path} shell`).toBeVisible();
      await expect(
        page.getByRole('heading', { name: heading }),
        `${path} heading`,
      ).toBeVisible({ timeout: 15_000 });
    }
  });
});

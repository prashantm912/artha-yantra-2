import { test, expect } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers.ts';

// React port of the parked Angular dashboard journey (frontend-react/src/pages/dashboard/DashboardPage.tsx).
// Intent preserved: /dashboard is the daily-driver cockpit — a status strip (System/Market pills) plus the
// three at-a-glance cards (Active Signals, Paper P&L, Jobs) with the "Open →" links into /signals and /paper.
// The Angular widget-element selectors (ay-market-overview-widget / ay-jobs-widget / p-table) are gone, so
// this asserts the React DOM: the sr-only h1, the section h2 headings (role=heading), and the deep links.
// Render-only check (no mutation). The card headings + Open links always render; the status strip renders
// once /system/status answers (it does in the mock stack), so it is asserted with a generous wait but the
// run also collects pageerror so a green render is a real green.

test.beforeAll(() => resetLoginLimiter());

test('dashboard renders the status strip, the cockpit cards, and the deep links', async ({ page }) => {
  const pageErrors: string[] = [];
  page.on('pageerror', (err) => pageErrors.push(String(err)));

  await loginThroughForm(page);

  await page.goto('/dashboard');
  await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

  // sr-only H1 anchors the page (always rendered)
  await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeAttached({
    timeout: 20_000,
  });

  // the three cockpit card headings — H2s, disambiguated by role so the empty-state copy
  // ("No live signals…", "No paper activity…", "No jobs running.") can't be mistaken for them.
  await expect(page.getByRole('heading', { name: 'Active Signals' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('heading', { name: 'Paper P&L' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('heading', { name: 'Jobs' })).toBeVisible({ timeout: 20_000 });

  // the deep links into the detail pages (the cards render an "Open →" Link each)
  await expect(page.getByRole('link', { name: 'Open →' })).toHaveCount(2, { timeout: 20_000 });
  await expect(page.getByRole('link', { name: 'Open →' }).first()).toHaveAttribute('href', '/signals');
  await expect(page.getByRole('link', { name: 'Open →' }).last()).toHaveAttribute('href', '/paper');

  // the status strip: the "System" pill renders once /system/status answers (worst-of Redis rollup,
  // always present in the mock stack). The pill label + market-phase pill are part of the same strip.
  await expect(page.getByText('System', { exact: false }).first()).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText('Market', { exact: false }).first()).toBeVisible({ timeout: 20_000 });

  // a green render is only green if nothing threw
  expect(pageErrors, `page errors: ${pageErrors.join('\n')}`).toEqual([]);
});

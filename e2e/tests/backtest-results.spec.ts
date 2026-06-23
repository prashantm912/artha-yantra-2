import { test, expect } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Ported from the parked Angular journey (e2e/tests-angular-parked/backtest-results.spec.ts). INTENT:
// prove the backtest cockpit renders and is wired — the runner (/backtests/run) and the live jobs
// monitor (/backtests/jobs). A fully-completed run that would populate BacktestResultsPage
// (/backtests/:id) is slow + needs benchmark history the boot-rolling mock stack lacks, so per the
// brief we DEGRADE: assert both anchor pages render their controls with no page errors. This is the
// deterministic, green path — the rich results screen is exercised by the React unit specs
// (BacktestResultsPage.spec.tsx) and the manual guide.

test.beforeAll(() => resetLoginLimiter());

test('the backtest runner and jobs monitor render their anchor controls without page errors', async ({
  page,
}) => {
  const pageErrors: Error[] = [];
  page.on('pageerror', (err) => pageErrors.push(err));

  await loginThroughForm(page);

  // --- runner (/backtests/run): the backtest tab + the strategy/interval controls + Run button ---
  await page.goto('/backtests/run');
  await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

  // capitalize is CSS-only, so the accessible tab name is the literal lowercase token
  const backtestTab = page.getByRole('tab', { name: 'backtest', exact: true });
  await expect(backtestTab).toBeVisible({ timeout: 20_000 });
  await expect(backtestTab).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('tab', { name: 'sweep', exact: true })).toBeVisible();

  // aria-labelled form controls (Select.tsx exposes ariaLabel; date/number inputs aria-label)
  await expect(page.getByLabel('Strategy')).toBeVisible();
  await expect(page.getByLabel('Interval')).toBeVisible();
  await expect(page.getByLabel('From')).toBeVisible();
  await expect(page.getByLabel('To')).toBeVisible();

  // the submit anchor — text is "▶ Run backtest"; accessible-name substring match clears the glyph
  await expect(page.getByRole('button', { name: 'Run backtest' })).toBeVisible({ timeout: 20_000 });

  // --- jobs monitor (/backtests/jobs): the Progress column + the Reload control ---
  await page.goto('/backtests/jobs');
  await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

  // the live-progress column header (the WS jobs.progress fan-out drives this column)
  await expect(page.getByRole('columnheader', { name: 'Progress' })).toBeVisible({ timeout: 20_000 });
  // other static headers confirm the table mounted, not just a stray cell
  await expect(page.getByRole('columnheader', { name: 'Status' })).toBeVisible();

  // the Reload control — text is "↻ Reload" (or "…" while fetching); match either accessible name
  await expect(
    page.getByRole('button', { name: /Reload|…/ }).first(),
  ).toBeVisible({ timeout: 20_000 });

  // no uncaught client errors across either page render
  expect(pageErrors, `page errors: ${pageErrors.map((e) => e.message).join(' | ')}`).toEqual([]);
});

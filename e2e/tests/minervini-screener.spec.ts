import { test, expect } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Audit M28: the /equity/minervini SEPA screener (frontend-react/src/pages/equity/MinerviniScreenerPage.tsx)
// had no e2e — including its two views: the flat 8-gate Trend-Template SCREEN and the SEPA FUNNEL that
// ranks passers into immediately-buyable / on-deck / watch. Equity screen data needs the EOD bhavcopy +
// universe the fresh mock stack lacks, so per the house pattern this DEGRADES: assert the page chrome +
// the screen controls, then that toggling to the Funnel tab re-scopes the view into a real settled
// state (the funnel columns, the "no funnel" empty copy, or the error alert) with no page errors.

test.beforeAll(() => resetLoginLimiter());

test('the Minervini screener renders the screen controls and toggles into the funnel view', async ({
  page,
}) => {
  const pageErrors: Error[] = [];
  page.on('pageerror', (err) => pageErrors.push(err));

  await loginThroughForm(page);

  await page.goto('/equity/minervini');
  await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

  // The single <h1> anchor.
  await expect(
    page.getByRole('heading', { level: 1, name: 'Minervini SEPA screener' }),
  ).toBeVisible({ timeout: 20_000 });

  // The view tablist — the Screen tab is selected first (accessible name is the lowercase token; the
  // capitalize is CSS-only, same as backtest-results.spec.ts).
  const screenTab = page.getByRole('tab', { name: 'screen', exact: true });
  const funnelTab = page.getByRole('tab', { name: 'funnel', exact: true });
  await expect(screenTab).toBeVisible({ timeout: 20_000 });
  await expect(screenTab).toHaveAttribute('aria-selected', 'true');
  await expect(funnelTab).toBeVisible();

  // Screen-view controls (only present on the Screen tab): the RS-rank floor + the Recompute action.
  await expect(page.getByLabel('Minimum RS-rank')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Recompute' })).toBeVisible();

  // Toggle to the Funnel view → the tab flips selected AND the funnel view resolves to a real state:
  // its triad columns (heading h3s) on data, else the empty/error copy. All are green "the view mounted".
  await funnelTab.click();
  await expect(funnelTab).toHaveAttribute('aria-selected', 'true');
  await expect(screenTab).toHaveAttribute('aria-selected', 'false');
  await expect(
    page
      .getByRole('heading', { name: 'Immediately buyable' })
      .or(page.getByText(/No funnel yet/))
      .or(page.getByTestId('qs-error'))
      .first(),
  ).toBeVisible({ timeout: 20_000 });

  expect(pageErrors, `page errors: ${pageErrors.map((e) => e.message).join(' | ')}`).toEqual([]);
});

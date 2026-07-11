import { test, expect } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Audit M28: the /signal-rejections page (frontend-react/src/pages/signals/RejectionsPage.tsx) — WHY
// the live §12.3 confluence gate blocked each scalper chart-entry (the failing rail, its margin, and
// the expandable dot-by-dot Connect-the-Dots breakdown) — had no e2e. Rejection rows are LIVE-only
// (the mock stack + backtests never run the gate), so per the house pattern (backtest-results.spec.ts)
// this DEGRADES to the deterministic green path: the page chrome + its two always-rendered filter
// controls render with no page errors. The rich expand/breakdown is covered by the React unit specs.

test.beforeAll(() => resetLoginLimiter());

test('the signal-rejections page renders its header + day/rail filters without page errors', async ({
  page,
}) => {
  const pageErrors: Error[] = [];
  page.on('pageerror', (err) => pageErrors.push(err));

  await loginThroughForm(page);

  await page.goto('/signal-rejections');
  await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

  // The single <h1> anchor.
  await expect(
    page.getByRole('heading', { level: 1, name: 'Signal Rejections' }),
  ).toBeVisible({ timeout: 20_000 });

  // The two always-rendered filter controls (DateInput + Select both expose aria-label).
  await expect(page.getByLabel('Rejection day')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByLabel('Filter by blocking rail')).toBeVisible();

  // Either the live rejections table or the "no blocked entries" empty copy resolves — both are green;
  // demanding a specific one would flake on whether today happened to record a blocked entry.
  await expect(
    page
      .getByRole('region', { name: 'Signal rejections table' })
      .or(page.getByText(/No blocked entries/))
      .first(),
  ).toBeVisible({ timeout: 20_000 });

  expect(pageErrors, `page errors: ${pageErrors.map((e) => e.message).join(' | ')}`).toEqual([]);
});

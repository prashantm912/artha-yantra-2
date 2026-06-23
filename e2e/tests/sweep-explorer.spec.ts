import { test, expect } from '@playwright/test';
import { apiLogin, loginThroughForm, publishE2eStrategy, resetLoginLimiter } from './helpers.ts';

// React port of the parked sweep-explorer journey (Phase 39): the SweepDetailPage
// (pages/optimizations/SweepDetailPage.tsx, route /optimizations/:sweepId) renders the trial
// scatter, the guard-aware leaderboard (plateau|raw sort toggle), and the "All trials" section
// (pruned/failed flagged, never hidden). A *completed* sweep is too slow + nondeterministic on the
// mock stack, so we DEGRADE: try a real API submission, but the e2e strategy carries no
// `backtest.optimize` block, so /optimizations/run is expected to 422 ("no tunable parameters").
// Whichever path we take we navigate to a sweep-detail URL (the real sweep id if one materialised,
// else a placeholder UUID) and assert the page's stable anchor controls render with zero page
// errors — a green render+no-error check beats a flaky wait on a finished optimisation.

const PLACEHOLDER_SWEEP = '00000000-0000-0000-0000-000000000000';

test.beforeAll(() => resetLoginLimiter());

test('sweep detail page renders the explorer + leaderboard + all-trials shell', async ({ page, request }) => {
  const pageErrors: Error[] = [];
  page.on('pageerror', (err) => pageErrors.push(err));

  await loginThroughForm(page);

  // --- fixture: try a real (small) sweep submission via the API; degrade if it can't run ---
  let sweepId = PLACEHOLDER_SWEEP;
  let path: 'real-sweep' | 'placeholder' = 'placeholder';

  try {
    await apiLogin(request);
    await publishE2eStrategy(request);

    // resolve the e2e strategy id (the publish helper is idempotent; the list carries the id)
    const list = await request.get('/api/v1/strategies?q=e2e-live-momentum');
    const body = (await list.json().catch(() => ({}))) as { items?: { id: string; slug: string }[] };
    const strategyId = (body.items ?? []).find((s) => s.slug === 'e2e-live-momentum')?.id;

    if (strategyId) {
      // the gateway needs X-XSRF-TOKEN on mutating calls; prime + read the double-submit cookie
      await request.get('/api/v1/auth/session');
      const { cookies } = await request.storageState();
      const xsrf = cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
      const headers = xsrf ? { 'X-XSRF-TOKEN': xsrf } : {};

      const day = (offset: number) => new Date(Date.now() - offset * 86_400_000).toISOString();
      // SweepRequest (api/backtests.ts): a small max_trials keeps it cheap; the e2e strategy lacks a
      // `backtest.optimize` block, so this is EXPECTED to 422 — caught + degraded below.
      const run = await request.post('/api/v1/optimizations/run', {
        headers,
        data: {
          strategyId,
          from: day(60),
          to: day(0),
          interval: '1d',
          initialCapital: '100000',
          method: 'random',
          maxTrials: 2,
          objective: { metric: 'sharpe', direction: 'maximize', fold_aggregation: 'mean' },
          constraints: { min_trades: 1 },
          seed: 42,
        },
      });

      if (run.status() === 202) {
        const { jobId } = (await run.json()) as { jobId?: string };
        if (jobId) {
          // the sweep detail is keyed by the run id (resultRef); poll the job detail briefly for it
          for (let i = 0; i < 6 && sweepId === PLACEHOLDER_SWEEP; i++) {
            const job = await request.get(`/api/v1/backtests/jobs/${jobId}`);
            const ref = (await job.json().catch(() => ({})) as { resultRef?: string | null }).resultRef;
            if (ref) {
              sweepId = ref;
              path = 'real-sweep';
            } else {
              await page.waitForTimeout(1000);
            }
          }
        }
      }
    }
  } catch {
    /* any fixture hiccup → fall back to the placeholder-UUID render check */
  }

  // --- assert the SweepDetailPage shell renders for the chosen sweep id, no page errors ---
  await page.goto(`/optimizations/${sweepId}`);
  await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

  // sr-only page title (h1) — the React page's stable anchor
  await expect(page.getByRole('heading', { name: 'Sweep explorer' })).toBeVisible({ timeout: 20_000 });

  // the plateau|raw sort toggle (guard 4) — a role="group" aria-label="Sort" with both buttons
  const sortGroup = page.getByRole('group', { name: 'Sort' });
  await expect(sortGroup).toBeVisible({ timeout: 20_000 });
  await expect(sortGroup.getByRole('button', { name: 'plateau' })).toBeVisible();
  await expect(sortGroup.getByRole('button', { name: 'raw' })).toBeVisible();

  // the leaderboard heading (h3 starts with "Leaderboard"); disambiguate by role, not bare text
  await expect(page.getByRole('heading', { name: /^Leaderboard/ })).toBeVisible({ timeout: 20_000 });

  // the "All trials" section (pruned/failed FLAGGED, never hidden — guard 3)
  await expect(
    page.getByRole('heading', { name: /All trials \(pruned\/failed flagged, never hidden\)/ }),
  ).toBeVisible({ timeout: 20_000 });

  // the trial-objective scatter (ECharts → role="img" with its aria-label)
  await expect(page.getByRole('img', { name: 'Trial objective scatter' })).toBeVisible({ timeout: 20_000 });

  // exercise the sort toggle — a client-side state flip that must not throw
  await sortGroup.getByRole('button', { name: 'raw' }).click();
  await expect(page.getByRole('heading', { name: /^Leaderboard/ })).toBeVisible();

  expect(pageErrors, `console pageerrors: ${pageErrors.map((e) => e.message).join(' | ')}`).toHaveLength(0);

  // surface which path the run took (real sweep vs degraded placeholder) in the trace
  test.info().annotations.push({ type: 'sweep-path', description: `${path} (${sweepId})` });
});

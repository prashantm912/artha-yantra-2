import { test, expect } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Audit M28: the /data-ops/ingest-health board (frontend-react/src/pages/dataops/IngestHealthPage.tsx,
// A11) — a read-only per-source EOD ingest-health view over the marketdata.ingest_runs ledger — was
// added after the audit with no e2e. Whether the mock ledger has rows is non-deterministic, so per the
// house pattern this DEGRADES: assert the page chrome renders and the query resolves to a real,
// non-crashing state (the source table, the empty card, or the error alert — all "the page mounted and
// the fetch completed"), with no page errors. The per-verdict colouring/sorting is unit-tested.

test.beforeAll(() => resetLoginLimiter());

test('the ingest-health board renders its header and a settled query state without page errors', async ({
  page,
}) => {
  const pageErrors: Error[] = [];
  page.on('pageerror', (err) => pageErrors.push(err));

  await loginThroughForm(page);

  await page.goto('/data-ops/ingest-health');
  await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

  // The single <h1> anchor.
  await expect(
    page.getByRole('heading', { level: 1, name: 'Ingest Health' }),
  ).toBeVisible({ timeout: 20_000 });

  // The QueryState resolved out of its skeleton into one of its three real states — proving the page
  // mounted and the /market/health/ingest read completed (not stuck loading / not thrown).
  await expect(
    page
      .getByRole('table', { name: 'Ingest health by source' })
      .or(page.getByText(/No data for this selection/))
      .or(page.getByTestId('qs-error'))
      .first(),
  ).toBeVisible({ timeout: 20_000 });

  expect(pageErrors, `page errors: ${pageErrors.map((e) => e.message).join(' | ')}`).toEqual([]);
});

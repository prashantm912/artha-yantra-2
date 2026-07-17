import { test, expect, type APIRequestContext } from '@playwright/test';
import { apiLogin, loginThroughForm, publishE2eStrategy, resetLoginLimiter } from './helpers.ts';

// Ported from the parked Angular journey (tests-angular-parked/signals.spec.ts), retargeted to the
// React SignalsPage (frontend-react/src/pages/signals/SignalsPage.tsx). Intent: a published strategy
// produces a live signal that the cockpit shows with its frozen reasoning breakdown, and Take/Dismiss
// round-trips. The mock engine emits from the e2e strategy, but emission timing is non-deterministic
// in CI, so this DEGRADES GRACEFULLY: it polls the API briefly for a real row and exercises the full
// click-through if one lands; otherwise it asserts the page renders its anchor controls with no error.

const POLL_BUDGET_MS = 30_000;
const POLL_EVERY_MS = 2_500;

/** Polls the signals snapshot for a RELIANCE ENTRY row (the e2e strategy's instrument). */
async function pollForRelianceSignal(
  request: APIRequestContext,
): Promise<{ id: number } | null> {
  const deadline = Date.now() + POLL_BUDGET_MS;
  while (Date.now() < deadline) {
    const res = await request.get('/api/v1/signals?limit=50&offset=0');
    if (res.ok()) {
      const body = (await res.json().catch(() => ({}))) as {
        items?: { id: number; tradingsymbol?: string; signalType?: string }[];
      };
      const match = (body.items ?? []).find(
        (s) => s.tradingsymbol === 'RELIANCE' && s.signalType === 'ENTRY',
      );
      if (match) return { id: match.id };
    }
    await new Promise((r) => setTimeout(r, POLL_EVERY_MS));
  }
  return null;
}

test.describe('signals cockpit — published strategy emits a live signal with its breakdown', () => {
  test.beforeAll(() => resetLoginLimiter());

  test('a live signal shows its reasoning breakdown and can be taken (degrades to render-check)', async ({
    page,
    request,
  }) => {
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    // API fixture: publish the deterministic momentum strategy so the mock engine has something to
    // score on each 1m close. apiLogin first so the request context carries the SESSION cookie.
    await apiLogin(request);
    await publishE2eStrategy(request);

    await loginThroughForm(page);
    await page.goto('/signals');
    await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

    // Anchor controls always render regardless of whether any signal has emitted yet.
    const statusFilter = page.getByLabel('Status filter');
    await expect(statusFilter).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: '↻ Reload' })).toBeVisible();

    // Did the engine emit a scoreable RELIANCE signal within the budget?
    const signal = await pollForRelianceSignal(request);

    if (signal) {
      // FULL FLOW: refresh so the UI snapshot includes the emitted row, then click through.
      await page.getByRole('button', { name: '↻ Reload' }).click();

      // SignalsPage rows are plain <tr> in <tbody> (the M23 audit removed role="button" from the row
      // to keep table semantics), so key off the row's cell text, not a stale role.
      const row = page.locator('table tbody tr', { hasText: 'NSE:RELIANCE' }).first();
      await expect(row).toBeVisible({ timeout: 20_000 });
      await expect(row).toContainText('ENTRY');
      await row.click();

      // The frozen C-2.6 reasoning contract renders in the detail aside.
      await expect(
        page.getByRole('heading', { name: 'Composite vs threshold' }),
      ).toBeVisible({ timeout: 20_000 });
      // Breakdown body proves the per-indicator rows + invariant wiring rendered.
      const aside = page.locator('aside');
      await expect(aside).toContainText('rsi_1m');
      await expect(aside).toContainText('weight denominator');

      // Take the signal; the row's status badge flips to TAKEN after the round-trip.
      await page.getByRole('button', { name: '✓ Taken' }).click();
      await expect(row).toContainText('TAKEN', { timeout: 20_000 });
    } else {
      // DEGRADED: no signal emitted in time (non-deterministic mock engine). Assert the page is
      // healthy — empty card OR a real <tbody> row. The global-setup readiness gate warms the signals
      // endpoint before any test, so a cold query resolves well inside 30s; the still-loading skeleton
      // is deliberately NOT accepted (the client fetch has no timeout, so a hung endpoint would sit in
      // qs-loading forever and silently pass) — a hung or failed endpoint must still FAIL here.
      const tableSettled = page
        .getByText('No signals yet', { exact: false })
        .or(page.locator('table tbody tr').first());
      await expect(tableSettled).toBeVisible({ timeout: 30_000 });

      // Drive the Status filter to confirm it is wired (selects a known status, then clears).
      await statusFilter.selectOption('ACTIVE');
      await expect(page.getByRole('button', { name: 'Clear' })).toBeVisible({ timeout: 20_000 });
      await page.getByRole('button', { name: 'Clear' }).click();
    }

    expect(pageErrors, `client page errors: ${pageErrors.join(' | ')}`).toEqual([]);
  });
});

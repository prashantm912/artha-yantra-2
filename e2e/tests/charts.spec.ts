import { test, expect } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Charts (master plan §20 parity, A13): /charts renders the instrument/interval toolbar and the
// lightweight-charts candlestick for the default NSE:NIFTY 50 @ 1d. React route: /charts
// (pages/charts/ChartsPage.tsx + components/charts/CandleChart.tsx). The candlestick is a
// role="img" div whose aria-label is `${symbol} ${interval} candlestick chart`, rendered ONLY when
// the cache-first /market/candles read returns bars. On a fresh mock stack daily candles can be
// sparse, so the chart may not paint — DEGRADE GRACEFULLY: always assert the anchor toolbar
// controls and require EITHER the candlestick OR the page's empty-state ("No candles…"/"Loading
// candles…"), with NO pageerror either way. Either outcome is a green render of the page.

test.describe('charts page (Phase 40, A13)', () => {
  test.beforeAll(() => resetLoginLimiter());

  test('renders the instrument/interval toolbar and the candlestick (or its empty-state) with no page errors', async ({
    page,
  }) => {
    const pageErrors: Error[] = [];
    page.on('pageerror', (err) => pageErrors.push(err));

    await loginThroughForm(page);

    // Default deep-link: NSE:NIFTY 50 @ 1d (matches the page's own defaults).
    await page.goto('/charts?symbol=NSE:NIFTY%2050&interval=1d');

    // Toolbar anchors — always present regardless of whether candles loaded.
    const instrument = page.getByLabel('Instrument');
    const intervalSelect = page.getByLabel('Interval');
    await expect(instrument).toBeVisible({ timeout: 20_000 });
    await expect(intervalSelect).toBeVisible({ timeout: 20_000 });

    // The toolbar carries the expected defaults.
    await expect(instrument).toHaveValue('NSE:NIFTY 50');
    await expect(intervalSelect).toHaveValue('1d');

    // The candlestick renders as role="img" (aria-label contains "candlestick") when bars load;
    // otherwise the page shows its loading/empty placeholder. Require one of the two so a sparse
    // mock-stack daily series can't flake the journey red.
    const candlestick = page.getByRole('img', { name: /candlestick/i });
    const emptyState = page.getByText(/No candles for|Loading candles/i);
    await expect(candlestick.or(emptyState).first()).toBeVisible({ timeout: 20_000 });

    // Give react-query a beat to settle (in case the chart is mid-fetch) and re-confirm one path
    // is rendered, then assert the page produced no uncaught errors.
    await expect
      .poll(async () => (await candlestick.count()) + (await emptyState.count()), { timeout: 20_000 })
      .toBeGreaterThan(0);

    expect(pageErrors, `unexpected page errors: ${pageErrors.map((e) => e.message).join(' | ')}`).toEqual([]);
  });
});

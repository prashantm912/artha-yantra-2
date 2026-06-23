import { test, expect } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Journey (ported from the parked Angular Phase-40C toolbar test): on the React /charts page, change
// the Interval native Select to another value and Load a different EXCHANGE:SYMBOL, then assert the
// instrument input reflects the new symbol and the toolbar/chart render without page errors.
//
// React shape (frontend-react/src/pages/charts/ChartsPage.tsx):
//   - instrument input  -> aria-label="Instrument" (default "NSE:NIFTY 50"), submitted via the Load button
//   - Load button       -> <button type="submit">Load</button>
//   - interval Select    -> native <select aria-label="Interval"> over CHART_INTERVALS 1m/5m/15m/1h/1d/1w (default 1d)
//   - chart             -> CandleChart renders <div role="img" aria-label="<symbol> <interval> candlestick chart">
//                          ONLY when bars.length > 0; otherwise a deterministic empty-state placeholder.
//
// Degraded path: the mock stack's interval caggs are sparse on a fresh boot, so an arbitrary
// symbol/interval may legitimately return zero candles (the page then shows "No candles for …").
// We therefore assert the toolbar reflects the new symbol + interval and that EITHER the candlestick
// chart (role=img) OR the empty-state placeholder is visible, with no page errors — a green
// render+no-error check beats a flaky dependence on mock candle coverage.

test.beforeAll(() => resetLoginLimiter());

test('charts toolbar: change interval + load a different EXCHANGE:SYMBOL', async ({ page }) => {
  const pageErrors: Error[] = [];
  page.on('pageerror', (err) => pageErrors.push(err));

  await loginThroughForm(page);
  await page.goto('/charts');

  // Toolbar anchors are present.
  const instrument = page.getByLabel('Instrument');
  const intervalSelect = page.getByLabel('Interval');
  const loadButton = page.getByRole('button', { name: 'Load', exact: true });
  await expect(instrument).toBeVisible({ timeout: 20_000 });
  await expect(intervalSelect).toBeVisible({ timeout: 20_000 });
  await expect(loadButton).toBeVisible({ timeout: 20_000 });

  // Default interval is 1d (unless a deep-link overrode it); pick a DIFFERENT value.
  const currentInterval = await intervalSelect.inputValue();
  const newInterval = currentInterval === '5m' ? '15m' : '5m';
  await intervalSelect.selectOption(newInterval);
  await expect(intervalSelect).toHaveValue(newInterval);

  // Load a different EXCHANGE:SYMBOL through the form (clear → type → Load button submits).
  const newSymbol = 'NSE:RELIANCE';
  await instrument.fill(newSymbol);
  await loadButton.click();

  // The instrument input reflects the new symbol (controlled by symbolDraft, set on submit).
  await expect(instrument).toHaveValue(newSymbol, { timeout: 20_000 });

  // The chart area resolves to either the candlestick chart (role=img) or the deterministic
  // empty-state placeholder for this symbol/interval — both prove the toolbar wiring rendered.
  const candlestick = page.getByRole('img', { name: /candlestick/i });
  const emptyState = page.getByText(new RegExp(`No candles for ${newSymbol}`, 'i'));
  await expect(candlestick.or(emptyState).first()).toBeVisible({ timeout: 20_000 });

  // If the chart did render, its aria-label must carry the freshly-loaded symbol + interval.
  if (await candlestick.isVisible().catch(() => false)) {
    await expect(candlestick).toHaveAttribute(
      'aria-label',
      new RegExp(`${newSymbol} ${newInterval} candlestick chart`, 'i'),
    );
  }

  expect(pageErrors, `page errors: ${pageErrors.map((e) => e.message).join(' | ')}`).toEqual([]);
});

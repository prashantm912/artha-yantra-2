import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch, listItems } from './client.ts';
import type { MarketCandle } from './types.ts';

// Vix & Index feed (§features/vix-index — oipulse "Vix & Price Chart"). ZERO backend: three reuse GETs to
// the existing cache-first /api/v1/market/candles for the three pinned NSE indices (INDIA VIX, NIFTY 50,
// NIFTY BANK) at 1m over the selected IST trading day, then a FE union-by-minute fold (core/vixIndexSeries).
// No new endpoint, no contract change. The candle params (exchange/tradingsymbol/interval/from/to) differ
// from the OI oiParams shape, so this is a standalone hook.

/** The three minute-series for the selected day; each is empty when nothing was captured (HTTP 200 []). */
export interface VixIndexData {
  vix: MarketCandle[];
  nifty: MarketCandle[];
  bankNifty: MarketCandle[];
}

/** The three pinned NSE index instruments the page always plots (exact tradingsymbols, embedded spaces). */
const VIX_SYMBOL = 'INDIA VIX';
const NIFTY_SYMBOL = 'NIFTY 50';
const BANKNIFTY_SYMBOL = 'NIFTY BANK';

/**
 * The IST trading-session window for a calendar day as offset-ISO datetimes. CandlesController binds
 * from/to as ISO DATE_TIME (a bare yyyy-MM-dd 400s), so we carry the +05:30 offset explicitly and span
 * the full session (09:00 pre-open → 15:40 just past close) — the candle store only holds session bars,
 * so a generous window simply returns whatever minutes were captured.
 */
function istDayBounds(dateIso: string): { from: string; to: string } {
  return { from: `${dateIso}T09:00:00+05:30`, to: `${dateIso}T15:40:00+05:30` };
}

/** One symbol's 1m candles for the window. 422 (DATA_GAP) is mapped to [] like the OI hooks; the normal
 * empty case (no minutes captured) is already a 200 with an empty items array. */
async function readCandles(tradingsymbol: string, from: string, to: string): Promise<MarketCandle[]> {
  const p = new URLSearchParams({ exchange: 'NSE', tradingsymbol, interval: '1m', from, to });
  try {
    const res = await apiFetch<{ items?: MarketCandle[] }>(`/market/candles?${p.toString()}`, {
      silenceToast: true,
    });
    return listItems(res);
  } catch (err) {
    if (err instanceof ApiError && err.status === 422) return [];
    throw err;
  }
}

/**
 * Reads all three index minute-series for the selected IST day in parallel. Keyed on the date; refetched
 * by the page's Go button. Disabled until a date is chosen.
 */
export function useVixIndex(dateIso: string) {
  return useQuery({
    queryKey: ['market', 'vix-index', dateIso],
    queryFn: async (): Promise<VixIndexData> => {
      const { from, to } = istDayBounds(dateIso);
      const [vix, nifty, bankNifty] = await Promise.all([
        readCandles(VIX_SYMBOL, from, to),
        readCandles(NIFTY_SYMBOL, from, to),
        readCandles(BANKNIFTY_SYMBOL, from, to),
      ]);
      return { vix, nifty, bankNifty };
    },
    enabled: !!dateIso,
  });
}

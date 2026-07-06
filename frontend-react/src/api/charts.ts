// Charts cockpit data layer (master plan §20 parity, A13). A focused ECharts candlestick view: candles
// from the cache-first /market/candles read, plus the engine-overlay marks the "View on chart"
// deep-links carry — backtest trades (by runId) or signals (by symbol). The Angular page used
// lightweight-charts with a streaming datafeed + indicator overlays; React has no LWC, so this is an
// ECharts MVP (the premium chart — LWC / openalgo-chart / TradingView — is a later swap). Indicator
// overlays + the live streaming datafeed are deferred.

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';
import { useCalendarStatus } from './marketCalendar.ts';
import { isMarketHoursIst } from '../lib/marketHours.ts';
import type { MarketCandle } from './types.ts';

export const CHART_INTERVALS = ['1m', '3m', '5m', '15m', '1h', '1d', '1w'] as const;

const SPAN_MS: Record<string, number> = {
  '1m': 60_000,
  '3m': 180_000,
  '5m': 300_000,
  '15m': 900_000,
  '1h': 3_600_000,
  '1d': 86_400_000,
  '1w': 604_800_000,
};

/** A normalized chart mark (entry/exit) the candle overlay annotates. */
export interface ChartMark {
  timeIso: string;
  price: string;
  label: string;
  bullish: boolean;
}

interface SignalLike {
  id: number;
  exchange: string;
  tradingsymbol: string;
  side: string;
  signalType: string;
  entryPrice: string | null;
  generatedAt: string;
}

function split(symbol: string) {
  const sep = symbol.indexOf(':');
  return { exchange: symbol.slice(0, sep), tradingsymbol: symbol.slice(sep + 1) };
}

// Intervals the /market/candles endpoint serves natively (native 1m + the caggs). 3m is NOT one of
// them (no candles_3m cagg), so the chart fetches the base 1m and rolls it up client-side.
const ROLLUP: Record<string, { base: string; factor: number }> = {
  '3m': { base: '1m', factor: 3 },
};

/** Aggregate ascending base bars into `factor`-minute OHLCV candles (epoch-floored buckets). */
function rollupCandles(bars: MarketCandle[], factor: number, label: string): MarketCandle[] {
  const groups = new Map<number, MarketCandle[]>();
  for (const b of bars) {
    const key = Math.floor(Math.floor(Date.parse(b.bucket) / 60000) / factor);
    const g = groups.get(key);
    if (g) g.push(b);
    else groups.set(key, [b]);
  }
  return [...groups.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([, g]) => ({
      ...g[0],
      interval: label,
      open: g[0].open,
      high: String(Math.max(...g.map((x) => Number(x.high)))),
      low: String(Math.min(...g.map((x) => Number(x.low)))),
      close: g[g.length - 1].close,
      volume: g.reduce((s, x) => s + (Number(x.volume) || 0), 0),
      oi: g[g.length - 1].oi,
    }));
}

/** Last ~220 bars of the symbol at the interval (cache-first read). */
export function useCandles(symbol: string, interval: string) {
  const { exchange, tradingsymbol } = split(symbol);
  // Anchor the window to the last trading session's close, not `now` — the recent [now-N, now] window
  // is empty on a weekend/holiday, so intraday (1m/5m/…) charts read blank on a non-trading day even
  // though the bars exist for the last session. Falls back to `now` until the calendar resolves.
  const lastDay = useCalendarStatus().data?.lastTradingDay;
  const roll = ROLLUP[interval];
  const intraday = interval !== '1d' && interval !== '1w';
  return useQuery({
    queryKey: ['candles', symbol, interval, lastDay ?? null],
    // Auto-refresh the series during the IST cash session so intraday candles advance without a
    // manual reload (the streaming datafeed is still deferred — this is the polite-poll bridge, the
    // documented sole consumer of isMarketHoursIst). ~10s picks up each newly-completed bar and
    // refreshes the still-forming one; daily/weekly and off-hours stay a one-shot fetch. Since queryFn
    // recomputes `to = now` each run, every poll advances the window to the latest bar.
    refetchInterval: intraday ? () => (isMarketHoursIst() ? 10_000 : false) : false,
    queryFn: async () => {
      const span = SPAN_MS[interval] ?? SPAN_MS['1d'];
      // Anchor `to` at the EARLIER of the last session's 15:30 close and now. On a weekend/holiday
      // the last trading day is in the past, so we read that session (the original intent). But
      // DURING a live session lastTradingDay == today, so today's 15:30 close is in the FUTURE —
      // clamping to `now` is essential, else the whole [from,to] window is future and intraday
      // charts read blank until ~afternoon (owner-reported 2026-07-06: NIFTY26JULFUT 1m empty at
      // 10:01 IST because the window was 11:50→15:30 IST).
      const now = new Date();
      const close =
        lastDay && lastDay.length === 10 ? new Date(`${lastDay}T15:30:00+05:30`) : now;
      const to = close.getTime() < now.getTime() ? close : now;
      const from = new Date(to.getTime() - span * 220);
      const p = new URLSearchParams({
        exchange,
        tradingsymbol,
        interval: roll ? roll.base : interval,
        from: from.toISOString(),
        to: to.toISOString(),
        limit: String(roll ? 220 * roll.factor + 10 : 250),
      });
      const res = await apiFetch<{ items: MarketCandle[] }>(`/market/candles?${p.toString()}`);
      return roll ? { ...res, items: rollupCandles(res.items, roll.factor, interval) } : res;
    },
    enabled: !!exchange && !!tradingsymbol,
  });
}

/** Fetch ~220 bars ending just before `before` — the lazy-load-older window (#D). Empty when none. */
export async function fetchOlderCandles(
  symbol: string,
  interval: string,
  before: Date,
): Promise<MarketCandle[]> {
  const { exchange, tradingsymbol } = split(symbol);
  if (!exchange || !tradingsymbol) return [];
  const span = SPAN_MS[interval] ?? SPAN_MS['1d'];
  const roll = ROLLUP[interval];
  const to = new Date(before.getTime() - 1000); // exclusive of the earliest loaded bar
  // Look back far enough to cross the overnight/weekend gap — an intraday window of span*220 lands in
  // pre-market and returns NOTHING (that's why minute charts wouldn't scroll back; daily worked because
  // its 220-day window has data). The backend returns ALL bars in [from,to] (gaps are empty), so a wide
  // window + a high limit yields the previous session(s), contiguous with the current earliest.
  const from = new Date(to.getTime() - Math.max(span * 220, 5 * 86_400_000));
  const p = new URLSearchParams({
    exchange,
    tradingsymbol,
    interval: roll ? roll.base : interval,
    from: from.toISOString(),
    to: to.toISOString(),
    limit: '5000',
  });
  const res = await apiFetch<{ items: MarketCandle[] }>(`/market/candles?${p.toString()}`);
  return roll ? rollupCandles(res.items, roll.factor, interval) : res.items;
}

/** Signals on this symbol → entry marks (the no-runId deep-link path). */
export function useChartSignals(symbol: string, enabled: boolean) {
  return useQuery({
    queryKey: ['chart-signals', symbol],
    queryFn: () => apiFetch<{ items: SignalLike[] }>('/signals?limit=200'),
    enabled,
    select: (r): ChartMark[] =>
      (r.items ?? [])
        .filter((s) => `${s.exchange}:${s.tradingsymbol}` === symbol && s.entryPrice)
        .map((s) => ({
          timeIso: s.generatedAt,
          price: s.entryPrice as string,
          label: `${s.signalType} ${s.side}`,
          bullish: s.side === 'BUY',
        })),
  });
}

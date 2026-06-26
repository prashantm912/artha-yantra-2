// Charts cockpit data layer (master plan §20 parity, A13). A focused ECharts candlestick view: candles
// from the cache-first /market/candles read, plus the engine-overlay marks the "View on chart"
// deep-links carry — backtest trades (by runId) or signals (by symbol). The Angular page used
// lightweight-charts with a streaming datafeed + indicator overlays; React has no LWC, so this is an
// ECharts MVP (the premium chart — LWC / openalgo-chart / TradingView — is a later swap). Indicator
// overlays + the live streaming datafeed are deferred.

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';
import { useCalendarStatus } from './marketCalendar.ts';
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

/** Last ~220 bars of the symbol at the interval (cache-first read). */
export function useCandles(symbol: string, interval: string) {
  const { exchange, tradingsymbol } = split(symbol);
  // Anchor the window to the last trading session's close, not `now` — the recent [now-N, now] window
  // is empty on a weekend/holiday, so intraday (1m/5m/…) charts read blank on a non-trading day even
  // though the bars exist for the last session. Falls back to `now` until the calendar resolves.
  const lastDay = useCalendarStatus().data?.lastTradingDay;
  return useQuery({
    queryKey: ['candles', symbol, interval, lastDay ?? null],
    queryFn: () => {
      const span = SPAN_MS[interval] ?? SPAN_MS['1d'];
      const to =
        lastDay && lastDay.length === 10 ? new Date(`${lastDay}T15:30:00+05:30`) : new Date();
      const from = new Date(to.getTime() - span * 220);
      const p = new URLSearchParams({
        exchange,
        tradingsymbol,
        interval,
        from: from.toISOString(),
        to: to.toISOString(),
        limit: '250',
      });
      return apiFetch<{ items: MarketCandle[] }>(`/market/candles?${p.toString()}`);
    },
    enabled: !!exchange && !!tradingsymbol,
  });
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

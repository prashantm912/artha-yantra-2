import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CandleChart } from '../../components/charts/CandleChart.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  CHART_INTERVALS,
  fetchOlderCandles,
  useCandles,
  useChartSignals,
  type ChartMark,
} from '../../api/charts.ts';
import { useBacktestTrades } from '../../api/backtests.ts';
import { useInstrumentSearch } from '../../api/watchlists.ts';
import type { MarketCandle } from '../../api/types.ts';

// /charts (master plan §20 parity, A13): a lightweight-charts candlestick + volume view (the plan's
// premium chart, MIT — replaced the ECharts MVP) with the interval/instrument toolbar and the
// engine-overlay marks the "View on chart" deep-links carry — backtest trades (?runId) or signals on
// the symbol. Indicator overlays + a live streaming datafeed are deferred.

export function ChartsPage() {
  const [params] = useSearchParams();
  const runId = params.get('runId');
  const [symbol, setSymbol] = useState(params.get('symbol') ?? 'NSE:NIFTY 50');
  const [interval, setInterval] = useState(params.get('interval') ?? '1d');
  const [symbolDraft, setSymbolDraft] = useState(symbol);

  // Live instrument typeahead (same /instruments/search as the watchlist picker). The draft also
  // accepts a raw EXCHANGE:SYMBOL typed + Enter; the dropdown shows when the draft is a search term
  // (not the already-loaded symbol) and there are hits.
  const hits = useInstrumentSearch(symbolDraft);
  const showHits = symbolDraft.trim().length >= 2 && symbolDraft !== symbol && (hits.data?.length ?? 0) > 0;
  function pick(exchange: string, tradingsymbol: string) {
    const s = `${exchange}:${tradingsymbol}`;
    setSymbolDraft(s);
    setSymbol(s);
  }

  const candles = useCandles(symbol, interval);
  const trades = useBacktestTrades(runId ?? '');
  const signalMarks = useChartSignals(symbol, !runId);

  // Lazy-loaded older bars (#D), prepended to the primary window. Reset whenever the primary query
  // changes (new symbol / interval / last-session window).
  const [older, setOlder] = useState<MarketCandle[]>([]);
  const loadingOlder = useRef(false);
  const noMoreOlder = useRef(false);
  useEffect(() => {
    setOlder([]);
    loadingOlder.current = false;
    noMoreOlder.current = false;
  }, [symbol, interval, candles.data]);

  const bars = useMemo(() => {
    const base = candles.data?.items ?? [];
    if (!older.length) return base;
    const byBucket = new Map<string, MarketCandle>();
    for (const b of older) byBucket.set(b.bucket, b);
    for (const b of base) byBucket.set(b.bucket, b);
    return [...byBucket.values()].sort((a, b) => Date.parse(a.bucket) - Date.parse(b.bucket));
  }, [candles.data, older]);

  const loadOlder = useCallback(() => {
    if (loadingOlder.current || noMoreOlder.current || !bars.length) return;
    loadingOlder.current = true;
    void fetchOlderCandles(symbol, interval, new Date(bars[0].bucket))
      .then((more) => {
        if (more.length) setOlder((prev) => [...more, ...prev]);
        else noMoreOlder.current = true;
      })
      .finally(() => {
        loadingOlder.current = false;
      });
  }, [bars, symbol, interval]);

  const marks: ChartMark[] = useMemo(() => {
    if (runId) {
      return (trades.data?.items ?? []).flatMap((t) => [
        { timeIso: t.entryTs, price: t.entryPrice, label: `#${t.seq} ${t.side} in`, bullish: t.side === 'LONG' },
        ...(t.exitTs && t.exitPrice
          ? [{ timeIso: t.exitTs, price: t.exitPrice, label: `#${t.seq} out`, bullish: t.side !== 'LONG' }]
          : []),
      ]);
    }
    return signalMarks.data ?? [];
  }, [runId, trades.data, signalMarks.data]);

  return (
    <LoadBeat>
      <PageHeader title="Charts" subtitle="Candlestick + volume — backtest-trade & signal overlays via deep-link" help="A candlestick + volume price chart for any instrument and interval; markers overlay backtest trades or signals when opened from a deep-link." />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (symbolDraft.trim()) setSymbol(symbolDraft.trim());
          }}
          className="flex items-center gap-2"
        >
          <div className="relative">
            <input
              value={symbolDraft}
              onChange={(e) => setSymbolDraft(e.target.value)}
              placeholder="Search instrument… or EXCHANGE:SYMBOL"
              aria-label="Instrument"
              title="Search for an instrument, or type EXCHANGE:SYMBOL and press Enter to chart it."
              className="h-9 w-full sm:w-64 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text"
            />
            {showHits && (
              <ul className="absolute z-20 mt-1 max-h-64 w-full min-w-56 overflow-auto rounded-md border border-ay-border bg-surface-1 shadow-lg">
                {(hits.data ?? []).slice(0, 20).map((h) => (
                  <li key={`${h.exchange}:${h.tradingsymbol}`}>
                    <button
                      type="button"
                      onClick={() => pick(h.exchange, h.tradingsymbol)}
                      className="block w-full px-3 py-1.5 text-left text-sm hover:bg-surface-2"
                    >
                      {h.exchange}:{h.tradingsymbol} {h.name && <span className="text-ay-muted">— {h.name}</span>}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <button type="submit" title="Load the chart for the entered instrument." className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent">
            Load
          </button>
        </form>
        <Select value={interval} options={[...CHART_INTERVALS]} onChange={setInterval} ariaLabel="Interval" title="Candle timeframe — the time span each candlestick covers." />
        {runId && <span className="text-xs text-ay-muted">overlaying trades from run {runId.slice(0, 8)}</span>}
      </div>

      <BeatBlock>
        {bars.length > 0 ? (
          <CandleChart
            bars={bars}
            marks={marks}
            intraday={interval !== '1d' && interval !== '1w'}
            onReachStart={loadOlder}
            className="h-72 sm:h-96 lg:h-[460px]"
            ariaLabel={`${symbol} ${interval} candlestick chart`}
          />
        ) : (
          <div className="grid h-72 sm:h-96 lg:h-[460px] place-items-center rounded-lg border border-ay-border text-ay-muted">
            {candles.isLoading ? 'Loading candles…' : `No candles for ${symbol} @ ${interval}.`}
          </div>
        )}
      </BeatBlock>
    </LoadBeat>
  );
}

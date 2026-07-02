import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AdvanceChart } from '../../components/charts/AdvanceChart.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { CHART_INTERVALS, fetchOlderCandles, useCandles } from '../../api/charts.ts';
import { useInstrumentSearch } from '../../api/watchlists.ts';
import type { MarketCandle } from '../../api/types.ts';

// Advance Chart (oipulse §advance-chart) — the pro charting page on lightweight-charts with the default
// OiPulse study set (VWAP / VWMA 20 / SuperTrend 10,2 on price + volume MA, RSI 14 + SMA 14 below). A
// symbol typeahead + interval toolbar bind the cache-first /market/candles read; older bars lazy-load on
// scroll-back. The TradingView-binary extras (drawing tools, study-template save/load, OI-bar overlay,
// trade-history, audio alerts) are deferred — see core/indicators for the (tested) study math.

const LEGEND: { label: string; color: string }[] = [
  { label: 'VWAP', color: '#3b82f6' },
  { label: 'VWMA 20', color: '#f59e0b' },
  { label: 'SuperTrend 10,2', color: 'var(--ay-bull)' },
  { label: 'RSI 14 / SMA 14', color: '#8b5cf6' },
];

export function AdvanceChartPage() {
  const [params] = useSearchParams();
  const [symbol, setSymbol] = useState(params.get('symbol') ?? 'NSE:NIFTY 50');
  const [interval, setInterval] = useState(params.get('interval') ?? '5m');
  const [symbolDraft, setSymbolDraft] = useState(symbol);

  const hits = useInstrumentSearch(symbolDraft);
  const showHits = symbolDraft.trim().length >= 2 && symbolDraft !== symbol && (hits.data?.length ?? 0) > 0;
  function pick(exchange: string, tradingsymbol: string) {
    const s = `${exchange}:${tradingsymbol}`;
    setSymbolDraft(s);
    setSymbol(s);
  }

  const candles = useCandles(symbol, interval);

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

  return (
    <LoadBeat>
      <PageHeader
        title="Advance Chart"
        subtitle="Pro charting — candles with VWAP, VWMA, SuperTrend, volume MA and an RSI pane"
        help="A lightweight-charts study chart for any instrument and interval, with the default study set: VWAP, VWMA(20) and SuperTrend(10,2) over price plus an RSI(14) + SMA(14) sub-pane. Scroll back to lazy-load older bars."
      />

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
        <div className="ml-auto flex flex-wrap items-center gap-x-3 gap-y-1 text-caption text-ay-muted">
          {LEGEND.map((l) => (
            <span key={l.label} className="inline-flex items-center gap-1">
              <span aria-hidden="true" className="inline-block h-2 w-3 rounded-sm" style={{ backgroundColor: l.color }} />
              {l.label}
            </span>
          ))}
        </div>
      </div>

      <BeatBlock>
        {bars.length > 0 ? (
          <AdvanceChart
            bars={bars}
            intraday={interval !== '1d' && interval !== '1w'}
            onReachStart={loadOlder}
            className="h-[28rem] sm:h-[34rem] lg:h-[40rem]"
            ariaLabel={`${symbol} ${interval} advance chart`}
          />
        ) : (
          <div className="grid h-[28rem] place-items-center rounded-lg border border-ay-border text-ay-muted">
            {candles.isLoading ? 'Loading candles…' : `No candles for ${symbol} @ ${interval}.`}
          </div>
        )}
      </BeatBlock>
    </LoadBeat>
  );
}

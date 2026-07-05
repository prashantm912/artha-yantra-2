import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AdvanceChart, type TradeMark } from '../../components/charts/AdvanceChart.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { CHART_INTERVALS, fetchOlderCandles, useCandles } from '../../api/charts.ts';
import { useInstrumentSearch } from '../../api/watchlists.ts';
import { usePaperTrades } from '../../api/paper.ts';
import { loadChartPrefs, saveChartPrefs } from '../../core/chartPrefs.ts';
import type { MarketCandle } from '../../api/types.ts';

// Advance Chart (oipulse §advance-chart) — the pro charting page on lightweight-charts with the default
// OiPulse study set (VWAP / VWMA 20 / SuperTrend 10,2 on price + volume MA, RSI 14 + SMA 14 below). A
// symbol typeahead + interval toolbar bind the cache-first /market/candles read; older bars lazy-load on
// scroll-back. The feasible TradingView-binary extras ride an off-by-default extras toolbar: an OI-bar
// histogram overlay (from the candle `oi` field), paper trade-history entry/exit markers, a user-armed
// audio price alert (Web Audio beep — never auto-plays), and add/remove horizontal price lines. The
// view state (symbol/interval + all four extras) PERSISTS to localStorage (core/chartPrefs), so a
// reload restores the session — the ADR-A13 chart-state-save/load v1. Interactive freehand drawing
// tools (trendline/fib/rectangle) stay deferred — a large LWC-primitive/canvas lift with no consumer.
// Study math lives in core/indicators (tested); the alert crossing rule in core/priceAlert (tested).

/** Short CSP-safe confirmation beep via the Web Audio API (no external asset, no autoplay). */
function beep() {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = 880;
    gain.gain.value = 0.08;
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.18);
    osc.onended = () => void ctx.close();
  } catch {
    // Audio unavailable (autoplay policy / no output device) — the visual confirmation still shows.
  }
}

const LEGEND: { label: string; color: string }[] = [
  { label: 'VWAP', color: '#3b82f6' },
  { label: 'VWMA 20', color: '#f59e0b' },
  { label: 'SuperTrend 10,2', color: 'var(--ay-bull)' },
  { label: 'RSI 14 / SMA 14', color: '#8b5cf6' },
];

export function AdvanceChartPage() {
  const [params] = useSearchParams();
  const [prefs] = useState(loadChartPrefs); // one-shot localStorage read (lazy init)
  const [symbol, setSymbol] = useState(params.get('symbol') ?? prefs.symbol ?? 'NSE:NIFTY 50');
  const [interval, setInterval] = useState(params.get('interval') ?? prefs.interval ?? '5m');
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

  // ── Extras (default off/empty; a saved session restores them — ADR A13 chart-state persistence) ──
  const [showOi, setShowOi] = useState(prefs.showOi ?? false);
  const [showTrades, setShowTrades] = useState(prefs.showTrades ?? false);
  const [priceLines, setPriceLines] = useState<number[]>(prefs.priceLines ?? []);
  const [alertPrice, setAlertPrice] = useState<number | null>(prefs.alertPrice ?? null);
  const [alertDraft, setAlertDraft] = useState('');
  const [alertFired, setAlertFired] = useState(false);

  // Persist the view state so a reload restores the symbol/interval + extras (volatile React state
  // otherwise). A URL ?symbol/?interval still wins on next load (handled in the initializers above).
  useEffect(() => {
    saveChartPrefs({ symbol, interval, showOi, showTrades, priceLines, alertPrice });
  }, [symbol, interval, showOi, showTrades, priceLines, alertPrice]);

  const chartExchange = symbol.slice(0, symbol.indexOf(':'));
  const chartTradingsymbol = symbol.slice(symbol.indexOf(':') + 1);
  const paperTrades = usePaperTrades();
  // Filter the closed paper trades to the charted instrument, mapped to entry/exit marks.
  const tradeMarks: TradeMark[] = useMemo(() => {
    if (!showTrades) return [];
    return (paperTrades.data?.items ?? [])
      .filter((t) => t.exchange === chartExchange && t.tradingsymbol === chartTradingsymbol)
      .map((t) => ({ openedAt: t.openedAt, closedAt: t.closedAt, side: t.side, label: `#${t.id}` }));
  }, [showTrades, paperTrades.data, chartExchange, chartTradingsymbol]);

  const lastClose = bars.length ? Number(bars[bars.length - 1].close) : null;
  const addLineAtLast = () => {
    if (lastClose != null) setPriceLines((prev) => [...prev, lastClose]);
  };
  const armAlert = () => {
    const v = Number(alertDraft);
    if (Number.isFinite(v) && alertDraft.trim()) {
      setAlertPrice(v);
      setAlertFired(false);
    }
  };
  const disarmAlert = () => {
    setAlertPrice(null);
    setAlertFired(false);
  };
  const onAlertCross = useCallback(() => {
    beep();
    setAlertFired(true);
  }, []);

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

      {/* Extras toolbar — OI overlay, trade markers, horizontal lines, audio alert (all off/empty by default). */}
      <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
        <label className="inline-flex items-center gap-1.5 text-ay-text" title="Overlay the candle open-interest as a histogram (derivative symbols only).">
          <input type="checkbox" checked={showOi} onChange={(e) => setShowOi(e.target.checked)} className="accent-accent" />
          OI overlay
        </label>
        <label className="inline-flex items-center gap-1.5 text-ay-text" title="Mark your closed paper trades for this symbol as entry/exit arrows.">
          <input type="checkbox" checked={showTrades} onChange={(e) => setShowTrades(e.target.checked)} className="accent-accent" />
          Trade markers
        </label>
        <div className="inline-flex items-center gap-1.5">
          <button
            type="button"
            onClick={addLineAtLast}
            disabled={lastClose == null}
            title="Drop a horizontal line at the last price."
            className="h-8 rounded-md border border-ay-border px-2.5 text-sm hover:border-accent disabled:opacity-50"
          >
            + Line at last
          </button>
          {priceLines.length > 0 && (
            <button
              type="button"
              onClick={() => setPriceLines([])}
              title="Remove all horizontal lines."
              className="h-8 rounded-md border border-ay-border px-2.5 text-sm hover:border-accent"
            >
              Clear lines ({priceLines.length})
            </button>
          )}
        </div>
        <div className="inline-flex items-center gap-1.5">
          <label htmlFor="alert-price" className="text-ay-muted">Alert @</label>
          <input
            id="alert-price"
            type="number"
            inputMode="decimal"
            value={alertDraft}
            onChange={(e) => setAlertDraft(e.target.value)}
            placeholder="price"
            aria-label="Audio alert price"
            title="Play a beep once when the latest close crosses this level."
            className="h-8 w-24 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text"
          />
          {alertPrice == null ? (
            <button type="button" onClick={armAlert} title="Arm the audio price alert." className="h-8 rounded-md border border-ay-border px-2.5 text-sm hover:border-accent">
              Arm
            </button>
          ) : (
            <button type="button" onClick={disarmAlert} title="Disarm the audio price alert." className="h-8 rounded-md border border-ay-border px-2.5 text-sm hover:border-accent">
              Disarm
            </button>
          )}
          {/* Always mounted so the armed→crossed transition is announced (the beep alone is not a
              sufficient cue for a deaf user). */}
          <span className="text-caption text-ay-muted" role="status" aria-live="polite">
            {alertPrice == null ? '' : alertFired ? `crossed ${alertPrice}` : `armed @ ${alertPrice}`}
          </span>
        </div>
      </div>

      <BeatBlock>
        {bars.length > 0 ? (
          <AdvanceChart
            bars={bars}
            intraday={interval !== '1d' && interval !== '1w'}
            onReachStart={loadOlder}
            showOi={showOi}
            tradeMarks={tradeMarks}
            alertPrice={alertPrice}
            onAlertCross={onAlertCross}
            priceLines={priceLines}
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

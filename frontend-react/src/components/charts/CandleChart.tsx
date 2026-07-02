import { useEffect, useRef } from 'react';
import {
  CandlestickSeries,
  HistogramSeries,
  createChart,
  createSeriesMarkers,
  type IChartApi,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts';
import { cn } from '../../lib/cn.ts';
import type { MarketCandle } from '../../api/types.ts';
import type { ChartMark } from '../../api/charts.ts';

// lightweight-charts candlestick + volume (the plan's premium chart, MIT — replaces the ECharts
// candlestick MVP on /charts). Themed from the live --ay-* tokens, re-themed when data-theme flips on
// <html>; autoSize handles sizing. Trade/signal marks (deep-links) render via createSeriesMarkers
// (entry = arrow below/bull, exit/short = arrow above/bear). v5 API (>=5.2).

const sec = (iso: string) => Math.floor(Date.parse(iso) / 1000) as UTCTimestamp;

function vars(el: HTMLElement) {
  const s = getComputedStyle(el);
  const v = (n: string) => s.getPropertyValue(n).trim();
  return {
    text: v('--ay-text'),
    muted: v('--ay-text-muted'),
    grid: v('--ay-chart-grid'),
    border: v('--ay-border'),
    bull: v('--ay-bull'),
    bear: v('--ay-bear'),
  };
}

interface Props {
  bars: MarketCandle[];
  marks: ChartMark[];
  /** Fixed height; pass `undefined` and a responsive `h-*` className to drive height from CSS. */
  height?: number;
  ariaLabel: string;
  className?: string;
  /** Intraday interval (sub-daily) → the time axis shows HH:MM; daily/weekly shows dates. Without
   *  this, lightweight-charts labels every intraday bar with its date (e.g. "25 25 25…"). */
  intraday?: boolean;
  /** Called when the user scrolls/zooms near the LEFT edge — the page fetches + prepends older bars. */
  onReachStart?: () => void;
}

export function CandleChart({ bars, marks, height, ariaLabel, className, intraday = false, onReachStart }: Props) {
  const elRef = useRef<HTMLDivElement>(null);
  // Latest callback in a ref so the once-only chart-init effect never closes over a stale onReachStart.
  const onReachStartRef = useRef(onReachStart);
  onReachStartRef.current = onReachStart;
  const chartRef = useRef<IChartApi | null>(null);
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const markersRef = useRef<ISeriesMarkersPluginApi<Time> | null>(null);
  // The last bar (right edge) we fit to — so a lazy-load PREPEND (older bars, same right edge) doesn't
  // re-fit and zoom out; only a fresh dataset (new symbol/interval/window) re-fits.
  const lastFitRef = useRef<string | null>(null);

  // init once + theme (re-applied on data-theme flips)
  useEffect(() => {
    const el = elRef.current;
    if (!el) return;
    const chart = createChart(el, { autoSize: true });
    const candles = chart.addSeries(CandlestickSeries, {});
    const volume = chart.addSeries(HistogramSeries, { priceScaleId: 'vol', priceFormat: { type: 'volume' } });
    chart.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
    chartRef.current = chart;
    candleRef.current = candles;
    volRef.current = volume;

    const applyTheme = () => {
      const t = vars(el);
      chart.applyOptions({
        layout: { background: { color: 'transparent' }, textColor: t.muted },
        grid: { vertLines: { color: t.grid }, horzLines: { color: t.grid } },
        rightPriceScale: { borderColor: t.border },
        timeScale: { borderColor: t.border },
      });
      candles.applyOptions({
        upColor: t.bull,
        downColor: t.bear,
        wickUpColor: t.bull,
        wickDownColor: t.bear,
        borderVisible: false,
      });
      volume.applyOptions({ color: t.muted });
    };
    applyTheme();
    const mo = new MutationObserver(applyTheme);
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

    // Lazy-load older history when the user scrolls/zooms within ~10 bars of the left edge.
    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (range && range.from < 10) onReachStartRef.current?.();
    });
    return () => {
      mo.disconnect();
      chart.remove();
      chartRef.current = null;
    };
  }, []);

  // data + markers on change
  useEffect(() => {
    const candles = candleRef.current;
    const volume = volRef.current;
    if (!candles || !volume) return;
    // lightweight-charts renders the axis in UTC; shift bars by the IST offset so a 09:15 session
    // bar reads "09:15", not "03:45". Daily/weekly need the SAME shift (P1-11): their buckets are
    // IST-midnight instants (= 18:30Z of the PREVIOUS day), so unshifted the UTC axis labelled
    // every 1d/1w bar with the prior calendar date — Monday's session read as Sunday.
    const istShift = 19800;
    const tOf = (b: MarketCandle) => (sec(b.bucket) + istShift) as UTCTimestamp;
    candles.setData(
      bars.map((b) => ({ time: tOf(b), open: Number(b.open), high: Number(b.high), low: Number(b.low), close: Number(b.close) })),
    );
    // Index symbols report volume = 0 on every bar — hide the dead pane instead of rendering
    // an empty histogram lane (audit 2026-07-02 §5).
    const hasVolume = bars.some((b) => b.volume > 0);
    volume.applyOptions({ visible: hasVolume });
    volume.setData(hasVolume ? bars.map((b) => ({ time: tOf(b), value: b.volume })) : []);

    // map each mark to the nearest bar's time; markers must be unique-sorted ascending
    const nearestTime = (iso: string): UTCTimestamp | null => {
      if (!bars.length) return null;
      const target = Date.parse(iso);
      let best = bars[0];
      let bestDiff = Infinity;
      for (const b of bars) {
        const diff = Math.abs(Date.parse(b.bucket) - target);
        if (diff < bestDiff) {
          bestDiff = diff;
          best = b;
        }
      }
      return (sec(best.bucket) + istShift) as UTCTimestamp;
    };
    const el = elRef.current;
    const t = el ? vars(el) : { bull: '#16a34a', bear: '#ef4444' };
    const seen = new Set<number>();
    const seriesMarks: SeriesMarker<Time>[] = marks
      .map((m) => ({ time: nearestTime(m.timeIso), m }))
      .filter((x): x is { time: UTCTimestamp; m: ChartMark } => x.time !== null)
      .sort((a, b) => (a.time as number) - (b.time as number))
      .filter((x) => {
        const k = x.time as number;
        if (seen.has(k)) return false; // one marker per bar slot (LWC requires unique times)
        seen.add(k);
        return true;
      })
      .map(({ time, m }) => ({
        time,
        position: m.bullish ? 'belowBar' : 'aboveBar',
        color: m.bullish ? t.bull : t.bear,
        shape: m.bullish ? 'arrowUp' : 'arrowDown',
        text: m.label,
      }));
    markersRef.current ??= createSeriesMarkers(candles, []);
    markersRef.current.setMarkers(seriesMarks);
    // Fit only when the right edge changed (a fresh dataset) — NOT when older bars are prepended,
    // which would zoom out to fit everything and yank the user's scroll position.
    const lastBucket = bars.length ? bars[bars.length - 1].bucket : null;
    if (lastBucket && lastBucket !== lastFitRef.current) {
      chartRef.current?.timeScale().fitContent();
      lastFitRef.current = lastBucket;
    }
  }, [bars, marks, intraday]);

  // Intraday → show HH:MM on the time axis (daily/weekly keeps date labels).
  useEffect(() => {
    chartRef.current?.timeScale().applyOptions({ timeVisible: intraday, secondsVisible: false });
  }, [intraday]);

  return (
    <div
      ref={elRef}
      role="img"
      aria-label={ariaLabel}
      className={cn('w-full', className)}
      style={height != null ? { height } : undefined}
    />
  );
}

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
  height?: number;
  ariaLabel: string;
  className?: string;
}

export function CandleChart({ bars, marks, height = 460, ariaLabel, className }: Props) {
  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const markersRef = useRef<ISeriesMarkersPluginApi<Time> | null>(null);

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
    candles.setData(
      bars.map((b) => ({ time: sec(b.bucket), open: Number(b.open), high: Number(b.high), low: Number(b.low), close: Number(b.close) })),
    );
    volume.setData(bars.map((b) => ({ time: sec(b.bucket), value: b.volume })));

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
      return sec(best.bucket);
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
    if (bars.length) chartRef.current?.timeScale().fitContent();
  }, [bars, marks]);

  return <div ref={elRef} role="img" aria-label={ariaLabel} className={cn('w-full', className)} style={{ height }} />;
}

import { useEffect, useRef } from 'react';
import {
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
  createChart,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts';
import { cn } from '../../lib/cn.ts';
import type { MarketCandle } from '../../api/types.ts';

// The Minervini candidate analyzer chart (MV-4.4): daily candles + the 50/150/200-day MA overlays
// (the Trend-Template structure) + a volume underlay + a dashed price line at the VCP pivot (the buy
// trigger). Reuses the lightweight-charts v5 pattern from CandleChart (autoSize, --ay-* theming,
// +19800s IST shift so a daily IST-midnight bucket labels on its own calendar date). MA line colours
// are fixed + conventional (blue/amber/purple) with a legend — they are not semantic like the candle
// bull/bear, and stay legible on every theme.

const sec = (iso: string) => Math.floor(Date.parse(iso) / 1000) as UTCTimestamp;
const IST_SHIFT = 19_800; // +5:30 in seconds

const MA = [
  { period: 50, color: '#3b82f6', label: '50-day' },
  { period: 150, color: '#f59e0b', label: '150-day' },
  { period: 200, color: '#a855f7', label: '200-day' },
] as const;

function vars(el: HTMLElement) {
  const s = getComputedStyle(el);
  const v = (n: string) => s.getPropertyValue(n).trim();
  return {
    muted: v('--ay-text-muted'),
    grid: v('--ay-chart-grid'),
    border: v('--ay-border'),
    bull: v('--ay-bull'),
    bear: v('--ay-bear'),
    accent: v('--ay-accent'),
  };
}

/** Trailing simple moving average aligned to `vals`; null until `period` bars are available. */
function sma(vals: number[], period: number): (number | null)[] {
  const out: (number | null)[] = new Array(vals.length).fill(null);
  let sum = 0;
  for (let i = 0; i < vals.length; i++) {
    sum += vals[i];
    if (i >= period) sum -= vals[i - period];
    if (i >= period - 1) out[i] = sum / period;
  }
  return out;
}

interface Props {
  bars: MarketCandle[];
  pivot?: number | null;
  height?: number;
  ariaLabel: string;
  className?: string;
}

export function MinerviniCandidateChart({ bars, pivot, height, ariaLabel, className }: Props) {
  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const maRef = useRef<ISeriesApi<'Line'>[]>([]);
  const pivotLineRef = useRef<IPriceLine | null>(null);

  // init once + theme (re-applied on data-theme flips)
  useEffect(() => {
    const el = elRef.current;
    if (!el) return;
    const chart = createChart(el, { autoSize: true });
    const candles = chart.addSeries(CandlestickSeries, {});
    const volume = chart.addSeries(HistogramSeries, {
      priceScaleId: 'vol',
      priceFormat: { type: 'volume' },
    });
    chart.priceScale('vol').applyOptions({ scaleMargins: { top: 0.85, bottom: 0 } });
    const mas = MA.map((m) =>
      chart.addSeries(LineSeries, { color: m.color, lineWidth: 2, priceLineVisible: false, lastValueVisible: false }),
    );
    chartRef.current = chart;
    candleRef.current = candles;
    volRef.current = volume;
    maRef.current = mas;

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

  // data on change
  useEffect(() => {
    const candles = candleRef.current;
    const volume = volRef.current;
    if (!candles || !volume) return;
    const tOf = (b: MarketCandle) => (sec(b.bucket) + IST_SHIFT) as UTCTimestamp;
    candles.setData(
      bars.map((b) => ({
        time: tOf(b),
        open: Number(b.open),
        high: Number(b.high),
        low: Number(b.low),
        close: Number(b.close),
      })),
    );
    const hasVolume = bars.some((b) => b.volume > 0);
    volume.applyOptions({ visible: hasVolume });
    volume.setData(hasVolume ? bars.map((b) => ({ time: tOf(b), value: b.volume })) : []);

    const closes = bars.map((b) => Number(b.close));
    maRef.current.forEach((series, i) => {
      const line = sma(closes, MA[i].period);
      series.setData(
        bars
          .map((b, j) => ({ time: tOf(b), value: line[j] }))
          .filter((p): p is { time: UTCTimestamp; value: number } => p.value != null),
      );
    });

    if (pivotLineRef.current) {
      candles.removePriceLine(pivotLineRef.current);
      pivotLineRef.current = null;
    }
    if (pivot != null && Number.isFinite(pivot)) {
      const t = elRef.current ? vars(elRef.current) : null;
      pivotLineRef.current = candles.createPriceLine({
        price: pivot,
        color: t?.accent || '#22c55e',
        lineWidth: 1,
        lineStyle: 2, // dashed
        axisLabelVisible: true,
        title: 'pivot',
      });
    }
    chartRef.current?.timeScale().fitContent();
  }, [bars, pivot]);

  return (
    <div className={cn('relative', className)}>
      <div
        ref={elRef}
        role="img"
        aria-label={ariaLabel}
        style={height ? { height } : undefined}
        className={height ? undefined : 'h-full'}
      />
      <div className="pointer-events-none absolute left-2 top-2 flex gap-3 text-[11px]">
        {MA.map((m) => (
          <span key={m.period} className="flex items-center gap-1">
            <span className="inline-block h-0.5 w-3" style={{ backgroundColor: m.color }} aria-hidden="true" />
            <span className="text-ay-muted">{m.label}</span>
          </span>
        ))}
      </div>
    </div>
  );
}

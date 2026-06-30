import { useEffect, useRef } from 'react';
import {
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
  createChart,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts';
import { cn } from '../../lib/cn.ts';
import type { MarketCandle } from '../../api/types.ts';
import {
  rsi as computeRsi,
  smaOfLine,
  superTrend,
  volumeMa,
  vwap,
  vwma,
  type IndicatorBar,
  type LinePoint,
} from '../../core/indicators.ts';

// Advance Chart (oipulse §advance-chart) — a lightweight-charts pro chart with the default OiPulse study
// set: VWAP, VWMA(20), SuperTrend(10,2) on the price pane (candles + volume + volume MA), and RSI(14)
// with its SMA(14) signal + 70/30 guides in a second pane. The drawing tools / study-template save-load /
// OI-bar / trade-history toggles are the TradingView-binary extras and are deferred — this is the MIT-LWC
// replication the study calls for. Indicator math lives in core/indicators (tested); this component is the
// declarative render. Themed from --ay-* (re-applied on data-theme flips); intraday shifts to IST.

const sec = (iso: string) => Math.floor(Date.parse(iso) / 1000) as UTCTimestamp;

// Indicator line colours (conventional; SuperTrend rides the bull/bear theme tokens).
const C_VWAP = '#3b82f6';
const C_VWMA = '#f59e0b';
const C_RSI = '#8b5cf6';

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
  ariaLabel: string;
  className?: string;
  intraday?: boolean;
  onReachStart?: () => void;
}

export function AdvanceChart({ bars, ariaLabel, className, intraday = false, onReachStart }: Props) {
  const elRef = useRef<HTMLDivElement>(null);
  const onReachStartRef = useRef(onReachStart);
  onReachStartRef.current = onReachStart;
  const chartRef = useRef<IChartApi | null>(null);
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const volMaRef = useRef<ISeriesApi<'Line'> | null>(null);
  const vwapRef = useRef<ISeriesApi<'Line'> | null>(null);
  const vwmaRef = useRef<ISeriesApi<'Line'> | null>(null);
  const stUpRef = useRef<ISeriesApi<'Line'> | null>(null);
  const stDownRef = useRef<ISeriesApi<'Line'> | null>(null);
  const rsiRef = useRef<ISeriesApi<'Line'> | null>(null);
  const rsiSmaRef = useRef<ISeriesApi<'Line'> | null>(null);
  const lastFitRef = useRef<string | null>(null);

  // init once + theme
  useEffect(() => {
    const el = elRef.current;
    if (!el) return;
    const chart = createChart(el, { autoSize: true });
    const candles = chart.addSeries(CandlestickSeries, {});
    const volume = chart.addSeries(HistogramSeries, { priceScaleId: 'vol', priceFormat: { type: 'volume' } });
    chart.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
    const volMa = chart.addSeries(LineSeries, { priceScaleId: 'vol', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
    const vwapLine = chart.addSeries(LineSeries, { color: C_VWAP, lineWidth: 2, priceLineVisible: false, lastValueVisible: false, title: 'VWAP' });
    const vwmaLine = chart.addSeries(LineSeries, { color: C_VWMA, lineWidth: 2, priceLineVisible: false, lastValueVisible: false, title: 'VWMA 20' });
    const stUp = chart.addSeries(LineSeries, { lineWidth: 2, priceLineVisible: false, lastValueVisible: false });
    const stDown = chart.addSeries(LineSeries, { lineWidth: 2, priceLineVisible: false, lastValueVisible: false });
    // RSI in a second pane (index 1).
    const rsiLine = chart.addSeries(LineSeries, { color: C_RSI, lineWidth: 2, priceLineVisible: false, title: 'RSI 14' }, 1);
    const rsiSma = chart.addSeries(LineSeries, { lineWidth: 1, priceLineVisible: false, lastValueVisible: false, title: 'SMA 14' }, 1);
    rsiLine.createPriceLine({ price: 70, color: '#9ca3af', lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: '70' });
    rsiLine.createPriceLine({ price: 30, color: '#9ca3af', lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: '30' });
    try {
      const panes = chart.panes();
      panes[0]?.setStretchFactor(3);
      panes[1]?.setStretchFactor(1);
    } catch {
      // panes() unavailable on this build — the RSI pane keeps its default height.
    }

    chartRef.current = chart;
    candleRef.current = candles;
    volRef.current = volume;
    volMaRef.current = volMa;
    vwapRef.current = vwapLine;
    vwmaRef.current = vwmaLine;
    stUpRef.current = stUp;
    stDownRef.current = stDown;
    rsiRef.current = rsiLine;
    rsiSmaRef.current = rsiSma;

    const applyTheme = () => {
      const t = vars(el);
      chart.applyOptions({
        layout: { background: { color: 'transparent' }, textColor: t.muted },
        grid: { vertLines: { color: t.grid }, horzLines: { color: t.grid } },
        rightPriceScale: { borderColor: t.border },
        timeScale: { borderColor: t.border },
      });
      candles.applyOptions({ upColor: t.bull, downColor: t.bear, wickUpColor: t.bull, wickDownColor: t.bear, borderVisible: false });
      volume.applyOptions({ color: t.muted });
      volMa.applyOptions({ color: t.muted });
      stUp.applyOptions({ color: t.bull });
      stDown.applyOptions({ color: t.bear });
      rsiSma.applyOptions({ color: t.muted });
    };
    applyTheme();
    const mo = new MutationObserver(applyTheme);
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (range && range.from < 10) onReachStartRef.current?.();
    });
    return () => {
      mo.disconnect();
      chart.remove();
      chartRef.current = null;
    };
  }, []);

  // data on change
  useEffect(() => {
    const candles = candleRef.current;
    if (!candles || !chartRef.current) return;
    const istShift = intraday ? 19_800 : 0;
    const tOf = (b: MarketCandle) => (sec(b.bucket) + istShift) as UTCTimestamp;

    candles.setData(
      bars.map((b) => ({ time: tOf(b), open: Number(b.open), high: Number(b.high), low: Number(b.low), close: Number(b.close) })),
    );
    volRef.current?.setData(bars.map((b) => ({ time: tOf(b), value: b.volume })));

    const ind: IndicatorBar[] = bars.map((b) => ({
      time: tOf(b),
      open: Number(b.open),
      high: Number(b.high),
      low: Number(b.low),
      close: Number(b.close),
      volume: Number(b.volume) || 0,
    }));

    volMaRef.current?.setData(volumeMa(ind) as never);
    vwapRef.current?.setData(vwap(ind) as never);
    vwmaRef.current?.setData(vwma(ind) as never);

    // SuperTrend → two whitespace-broken lines so the up (green) and down (red) segments don't connect.
    const st = superTrend(ind);
    const byTime = new Map(st.map((p) => [p.time, p]));
    const up: LinePoint[] = ind.map((b) => {
      const p = byTime.get(b.time);
      return p && p.dir === 'up' ? { time: b.time, value: p.value } : { time: b.time };
    });
    const down: LinePoint[] = ind.map((b) => {
      const p = byTime.get(b.time);
      return p && p.dir === 'down' ? { time: b.time, value: p.value } : { time: b.time };
    });
    stUpRef.current?.setData(up as never);
    stDownRef.current?.setData(down as never);

    const rsiLine = computeRsi(ind);
    rsiRef.current?.setData(rsiLine as never);
    rsiSmaRef.current?.setData(smaOfLine(rsiLine, 14) as never);

    const lastBucket = bars.length ? bars[bars.length - 1].bucket : null;
    if (lastBucket && lastBucket !== lastFitRef.current) {
      chartRef.current.timeScale().fitContent();
      lastFitRef.current = lastBucket;
    }
  }, [bars, intraday]);

  useEffect(() => {
    chartRef.current?.timeScale().applyOptions({ timeVisible: intraday, secondsVisible: false });
  }, [intraday]);

  return <div ref={elRef} role="img" aria-label={ariaLabel} className={cn('w-full', className)} />;
}

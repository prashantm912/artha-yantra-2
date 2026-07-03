import { useEffect, useRef } from 'react';
import {
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
  createChart,
  createSeriesMarkers,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type SeriesMarker,
  type Time,
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
import { crossedThreshold } from '../../core/priceAlert.ts';

// Advance Chart (oipulse §advance-chart) — a lightweight-charts pro chart with the default OiPulse study
// set: VWAP, VWMA(20), SuperTrend(10,2) on the price pane (candles + volume + volume MA), and RSI(14)
// with its SMA(14) signal + 70/30 guides in a second pane. Indicator math lives in core/indicators
// (tested); this component is the declarative render. Themed from --ay-* (re-applied on data-theme flips);
// intraday shifts to IST.
// The feasible slice of the TradingView-binary extras rides optional, off-by-default props: an OI-histogram
// overlay (`showOi`, from the candle `oi` field), paper trade-history markers (`tradeMarks`, via
// createSeriesMarkers), an armed audio price alert (`alertPrice` + `onAlertCross`, crossing math in
// core/priceAlert) and user horizontal price lines (`priceLines`). Freehand drawing tools + study-template
// save/load stay deferred (large LWC-primitive lift, no consumer).

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

/** A paper trade to plot as an entry+exit marker pair on the charted symbol. */
export interface TradeMark {
  openedAt: string;
  closedAt: string;
  side: 'BUY' | 'SELL';
  label: string;
}

const C_OI = '#14b8a6'; // OI histogram (teal — distinct from the volume lane's muted grey)

interface Props {
  bars: MarketCandle[];
  ariaLabel: string;
  className?: string;
  intraday?: boolean;
  onReachStart?: () => void;
  /** Overlay the candle `oi` field as a histogram on its own price scale (off by default). */
  showOi?: boolean;
  /** Paper trades for the charted symbol → entry/exit arrows (empty by default). */
  tradeMarks?: TradeMark[];
  /** Armed audio-alert level → a horizontal guide + crossing detection (null = disarmed). */
  alertPrice?: number | null;
  /** Fired once when the latest close crosses `alertPrice` (the page plays the beep). */
  onAlertCross?: () => void;
  /** User-added horizontal price lines (empty by default). */
  priceLines?: number[];
}

export function AdvanceChart({
  bars,
  ariaLabel,
  className,
  intraday = false,
  onReachStart,
  showOi = false,
  tradeMarks,
  alertPrice = null,
  onAlertCross,
  priceLines,
}: Props) {
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
  const oiRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const markersRef = useRef<ISeriesMarkersPluginApi<Time> | null>(null);
  const alertLineRef = useRef<IPriceLine | null>(null);
  const userLinesRef = useRef<IPriceLine[]>([]);
  // Once-per-crossing debounce: the last close we evaluated the alert against (advances every bar).
  const prevCloseRef = useRef<number | null>(null);
  const onAlertCrossRef = useRef(onAlertCross);
  onAlertCrossRef.current = onAlertCross;
  const lastFitRef = useRef<string | null>(null);

  // init once + theme
  useEffect(() => {
    const el = elRef.current;
    if (!el) return;
    const chart = createChart(el, { autoSize: true });
    const candles = chart.addSeries(CandlestickSeries, {});
    // lastValueVisible off: index symbols report volume 0 — the histogram's "0" last-value label
    // was one of the stray cross-pane labels (audit §5).
    const volume = chart.addSeries(HistogramSeries, {
      priceScaleId: 'vol',
      priceFormat: { type: 'volume' },
      priceLineVisible: false,
      lastValueVisible: false,
    });
    chart.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
    // OI overlay on its own price scale (mirrors the volume lane; toggled + fed by the data effect,
    // starts hidden so the default render is byte-identical).
    const oi = chart.addSeries(HistogramSeries, {
      priceScaleId: 'oi',
      color: C_OI,
      priceFormat: { type: 'volume' },
      priceLineVisible: false,
      lastValueVisible: false,
      visible: false,
    });
    chart.priceScale('oi').applyOptions({ scaleMargins: { top: 0.7, bottom: 0.12 } });
    const volMa = chart.addSeries(LineSeries, { priceScaleId: 'vol', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
    const vwapLine = chart.addSeries(LineSeries, { color: C_VWAP, lineWidth: 2, priceLineVisible: false, lastValueVisible: false, title: 'VWAP' });
    const vwmaLine = chart.addSeries(LineSeries, { color: C_VWMA, lineWidth: 2, priceLineVisible: false, lastValueVisible: false, title: 'VWMA 20' });
    const stUp = chart.addSeries(LineSeries, { lineWidth: 2, priceLineVisible: false, lastValueVisible: false });
    const stDown = chart.addSeries(LineSeries, { lineWidth: 2, priceLineVisible: false, lastValueVisible: false });
    // RSI in a second pane (index 1), pinned to the full 0–100 oscillator range so the 70/30
    // guides and value labels always sit INSIDE the pane (they used to bleed across the boundary).
    const rsiLine = chart.addSeries(LineSeries, {
      color: C_RSI,
      lineWidth: 2,
      priceLineVisible: false,
      title: 'RSI 14',
      autoscaleInfoProvider: () => ({ priceRange: { minValue: 0, maxValue: 100 } }),
    }, 1);
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
    oiRef.current = oi;
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
        layout: {
          background: { color: 'transparent' },
          textColor: t.muted,
          // visible divider between the price and RSI panes (audit §5: the boundary read as one pane)
          panes: { separatorColor: t.border, separatorHoverColor: t.grid, enableResize: true },
        },
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
    // Index symbols report volume = 0 on every bar — hide the dead histogram lane (audit §5,
    // same rule as CandleChart).
    const hasVolume = bars.some((b) => b.volume > 0);
    volRef.current?.applyOptions({ visible: hasVolume });
    volRef.current?.setData(hasVolume ? bars.map((b) => ({ time: tOf(b), value: b.volume })) : []);

    // OI overlay: derivative candles carry an `oi` field (null on indices/equities). Only render when
    // toggled AND the feed actually has OI — indices never do, so the lane hides itself like volume.
    const hasOi = bars.some((b) => b.oi != null && b.oi > 0);
    const showOiLane = showOi && hasOi;
    oiRef.current?.applyOptions({ visible: showOiLane });
    oiRef.current?.setData(
      showOiLane ? bars.filter((b) => b.oi != null).map((b) => ({ time: tOf(b), value: b.oi as number })) : [],
    );

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

    // Audio alert: when armed, fire once as the latest close crosses the level (debounced by advancing
    // prevClose every bar so a single crossing beeps once, not on every re-render).
    const latestClose = bars.length ? Number(bars[bars.length - 1].close) : null;
    if (alertPrice != null && latestClose != null) {
      if (crossedThreshold(prevCloseRef.current, latestClose, alertPrice)) onAlertCrossRef.current?.();
      prevCloseRef.current = latestClose;
    } else {
      prevCloseRef.current = latestClose; // keep the baseline current while disarmed
    }

    const lastBucket = bars.length ? bars[bars.length - 1].bucket : null;
    if (lastBucket && lastBucket !== lastFitRef.current) {
      chartRef.current.timeScale().fitContent();
      lastFitRef.current = lastBucket;
    }
  }, [bars, intraday, showOi, alertPrice]);

  useEffect(() => {
    chartRef.current?.timeScale().applyOptions({ timeVisible: intraday, secondsVisible: false });
  }, [intraday]);

  // Trade-history markers: entry (arrowUp, bull for BUY) + exit (arrowDown) at the nearest bar to each
  // trade's open/close time. LWC requires markers unique-sorted ascending, so we snap-to-bar, dedupe per
  // slot and sort. Empty `tradeMarks` clears them — no marker plugin is created until the first non-empty.
  useEffect(() => {
    const candles = candleRef.current;
    const el = elRef.current;
    if (!candles) return;
    const marks = tradeMarks ?? [];
    if (!marks.length && !markersRef.current) return; // nothing to draw and nothing to clear
    const istShift = intraday ? 19_800 : 0;
    const nearestTime = (iso: string): UTCTimestamp | null => {
      if (!bars.length) return null;
      const target = Date.parse(iso);
      if (Number.isNaN(target)) return null;
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
    const t = el ? vars(el) : { bull: '#16a34a', bear: '#ef4444' };
    const raw: SeriesMarker<Time>[] = [];
    for (const m of marks) {
      const entry = nearestTime(m.openedAt);
      const exit = nearestTime(m.closedAt);
      if (entry != null)
        raw.push({ time: entry, position: 'belowBar', color: m.side === 'BUY' ? t.bull : t.bear, shape: 'arrowUp', text: `${m.label} in` });
      if (exit != null)
        raw.push({ time: exit, position: 'aboveBar', color: m.side === 'BUY' ? t.bear : t.bull, shape: 'arrowDown', text: `${m.label} out` });
    }
    const seen = new Set<number>();
    const seriesMarks = raw
      .sort((a, b) => (a.time as number) - (b.time as number))
      .filter((x) => {
        const k = x.time as number;
        if (seen.has(k)) return false; // one marker per bar slot (LWC requires unique times)
        seen.add(k);
        return true;
      });
    markersRef.current ??= createSeriesMarkers(candles, []);
    markersRef.current.setMarkers(seriesMarks);
  }, [tradeMarks, bars, intraday]);

  // Armed audio-alert level → a horizontal guide line (removed when disarmed).
  useEffect(() => {
    const candles = candleRef.current;
    if (!candles) return;
    if (alertLineRef.current) {
      candles.removePriceLine(alertLineRef.current);
      alertLineRef.current = null;
    }
    if (alertPrice != null) {
      const t = elRef.current ? vars(elRef.current) : { text: '#f59e0b' };
      alertLineRef.current = candles.createPriceLine({
        price: alertPrice,
        color: t.text || '#f59e0b',
        lineWidth: 1,
        lineStyle: 2,
        axisLabelVisible: true,
        title: 'alert',
      });
    }
  }, [alertPrice]);

  // User-added horizontal price lines — reconciled wholesale (remove all, re-add the current list).
  useEffect(() => {
    const candles = candleRef.current;
    if (!candles) return;
    for (const pl of userLinesRef.current) candles.removePriceLine(pl);
    userLinesRef.current = (priceLines ?? []).map((price) =>
      candles.createPriceLine({ price, color: '#9ca3af', lineWidth: 1, lineStyle: 0, axisLabelVisible: true }),
    );
  }, [priceLines]);

  return <div ref={elRef} role="img" aria-label={ariaLabel} className={cn('w-full', className)} />;
}

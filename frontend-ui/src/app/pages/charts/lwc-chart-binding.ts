import {
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
  createChart,
  type CandlestickData,
  type IChartApi,
  type ISeriesApi,
  type LineData,
  type LogicalRange,
  type MouseEventParams,
  type SeriesType,
  type Time,
} from 'lightweight-charts';
import { type Bar } from './datafeed/datafeed-core';
import { toChartTime } from './datafeed/timestamp';

/** Crosshair legend snapshot: OHLCV at the hovered bar + each active overlay's value. */
export interface CrosshairSnapshot {
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  overlays: Record<string, number>;
}

/** Instrument render hints for the price format (from the instrument master; sensible defaults). */
export interface PriceFormat {
  precision: number;
  minMove: number;
}

/** Distinct overlay line colours (cycled by add order). */
const OVERLAY_COLORS = ['#38bdf8', '#f59e0b', '#a78bfa', '#34d399', '#f472b6', '#facc15'];

/**
 * `LwcChartBinding` (E-10.2, A13): the ONLY main-chart code typed against lightweight-charts — it
 * lives inside `/charts` under the E-9 lint boundary. Owns the chart/series/pane lifecycle; maps the
 * library-agnostic core's internal Bars onto LWC. Decimal-string→number conversion happens HERE, at
 * the render boundary only. `attributionLogo` stays ON (Apache-2.0 license posture, E-9).
 */
export class LwcChartBinding {
  private readonly chart: IChartApi;
  private readonly candles: ISeriesApi<'Candlestick'>;
  private readonly volume: ISeriesApi<'Histogram'>;
  private bars: Bar[] = [];
  private readonly overlays = new Map<
    string,
    { series: ISeriesApi<SeriesType>; byMs: Map<number, number> }
  >();

  constructor(
    private readonly el: HTMLElement,
    private interval: string,
    price: PriceFormat,
  ) {
    const css = getComputedStyle(el);
    const token = (name: string, fallback: string): string =>
      css.getPropertyValue(name).trim() || fallback;
    const bull = token('--ay-bull', '#22c55e');
    const bear = token('--ay-bear', '#ef4444');
    const grid = token('--ay-chart-grid', '#1f2940');
    const text = token('--ay-text-muted', '#93a0bd');
    const crosshair = token('--ay-chart-crosshair', '#64748b');

    this.chart = createChart(el, {
      width: el.clientWidth || 640,
      height: el.clientHeight || 420,
      autoSize: false,
      layout: { background: { color: 'transparent' }, textColor: text, attributionLogo: true },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false },
      crosshair: { vertLine: { color: crosshair }, horzLine: { color: crosshair } },
    });
    this.candles = this.chart.addSeries(CandlestickSeries, {
      upColor: bull,
      downColor: bear,
      borderUpColor: bull,
      borderDownColor: bear,
      wickUpColor: bull,
      wickDownColor: bear,
      priceFormat: { type: 'price', precision: price.precision, minMove: price.minMove },
    });
    this.volume = this.chart.addSeries(HistogramSeries, {
      priceScaleId: 'vol',
      priceFormat: { type: 'volume' },
      color: grid,
    });
    this.chart.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
  }

  /** The underlying chart — exposed for overlay/marker code that also lives inside `/charts`. */
  api(): IChartApi {
    return this.chart;
  }

  setInterval(interval: string): void {
    this.interval = interval;
  }

  /** Initial load: replace the whole buffer + series. */
  setData(bars: Bar[]): void {
    this.bars = [...bars];
    this.render();
    this.chart.timeScale().fitContent();
  }

  /** Prepend an older page (pagination back-fill) and re-set. */
  prepend(older: Bar[]): void {
    if (older.length === 0) {
      return;
    }
    const earliest = this.bars.length ? this.bars[0].time : Infinity;
    this.bars = [...older.filter((b) => b.time < earliest), ...this.bars];
    this.render();
  }

  /** Live update of the in-progress (or late/amended) bar — the `update`/`historicalUpdate` path. */
  updateLast(bar: Bar): void {
    const last = this.bars[this.bars.length - 1];
    if (last && last.time === bar.time) {
      this.bars[this.bars.length - 1] = bar;
    } else if (!last || bar.time > last.time) {
      this.bars.push(bar);
    } else {
      return; // out-of-order older tick — ignore (the closed-bar refresh heals)
    }
    this.candles.update(this.toCandle(bar));
    this.volume.update({ time: this.toCandle(bar).time, value: bar.volume });
  }

  /** Fire `cb(oldestLoadedMs)` when the user scrolls near the left edge (pagination trigger). */
  onPageBack(cb: (oldestMs: number) => void): void {
    this.chart.timeScale().subscribeVisibleLogicalRangeChange((range: LogicalRange | null) => {
      if (range && range.from < 8 && this.bars.length) {
        cb(this.bars[0].time);
      }
    });
  }

  /**
   * Add or replace an engine-computed overlay (Phase 40C): a price-pane line (EMA/VWAP/…) or a
   * sub-pane oscillator (RSI/MACD_HIST/…). Values arrive as decimal strings + epoch-ms and are
   * converted at THIS render boundary. Overlay math is never computed client-side (S7).
   */
  setOverlay(
    key: string,
    render: 'line' | 'histogram',
    pane: 'price' | 'sub',
    points: { timeMs: number; value: string }[],
  ): void {
    const data: LineData<Time>[] = points.map((p) => ({
      time: toChartTime(p.timeMs, this.interval) as Time,
      value: Number(p.value),
    }));
    const byMs = new Map<number, number>(points.map((p) => [p.timeMs, Number(p.value)]));
    let entry = this.overlays.get(key);
    if (!entry) {
      const color = OVERLAY_COLORS[this.overlays.size % OVERLAY_COLORS.length];
      const paneIndex = pane === 'sub' ? 1 : 0;
      const series =
        render === 'histogram'
          ? this.chart.addSeries(HistogramSeries, { color }, paneIndex)
          : this.chart.addSeries(
              LineSeries,
              { color, lineWidth: 2, priceLineVisible: false, lastValueVisible: false },
              paneIndex,
            );
      entry = { series, byMs };
      this.overlays.set(key, entry);
    }
    entry.byMs = byMs;
    entry.series.setData(data);
  }

  removeOverlay(key: string): void {
    const entry = this.overlays.get(key);
    if (entry) {
      this.chart.removeSeries(entry.series);
      this.overlays.delete(key);
    }
  }

  /** Fire `cb` with the OHLCV + overlay values at the hovered bar (the crosshair legend). */
  subscribeCrosshair(cb: (snapshot: CrosshairSnapshot | null) => void): void {
    this.chart.subscribeCrosshairMove((param: MouseEventParams) => {
      const candle = param.seriesData.get(this.candles) as CandlestickData<Time> | undefined;
      if (!candle) {
        cb(null);
        return;
      }
      const overlays: Record<string, number> = {};
      for (const [key, entry] of this.overlays) {
        const d = param.seriesData.get(entry.series) as LineData<Time> | undefined;
        if (d && typeof d.value === 'number') {
          overlays[key] = d.value;
        }
      }
      const vol = param.seriesData.get(this.volume) as { value?: number } | undefined;
      cb({
        open: candle.open,
        high: candle.high,
        low: candle.low,
        close: candle.close,
        volume: vol?.value ?? 0,
        overlays,
      });
    });
  }

  /** The accessible "View as table" rows: recent bars + each overlay's value (E-7). */
  tableRows(limit = 250): Record<string, string>[] {
    const overlayKeys = [...this.overlays.keys()];
    return this.bars.slice(-limit).map((b) => {
      const row: Record<string, string> = {
        time: new Date(b.time).toISOString(),
        open: b.open,
        high: b.high,
        low: b.low,
        close: b.close,
        volume: String(b.volume),
      };
      for (const key of overlayKeys) {
        const v = this.overlays.get(key)!.byMs.get(b.time);
        row[key] = v == null ? '' : String(v);
      }
      return row;
    });
  }

  remove(): void {
    this.chart.remove();
  }

  private render(): void {
    this.candles.setData(this.bars.map((b) => this.toCandle(b)));
    this.volume.setData(this.bars.map((b) => ({ time: this.toCandle(b).time, value: b.volume })));
  }

  private toCandle(b: Bar): CandlestickData<Time> {
    return {
      time: toChartTime(b.time, this.interval) as Time,
      open: Number(b.open),
      high: Number(b.high),
      low: Number(b.low),
      close: Number(b.close),
    };
  }
}

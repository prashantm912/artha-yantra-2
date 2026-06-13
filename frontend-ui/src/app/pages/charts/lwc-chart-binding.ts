import {
  CandlestickSeries,
  HistogramSeries,
  createChart,
  type CandlestickData,
  type IChartApi,
  type ISeriesApi,
  type LogicalRange,
  type Time,
} from 'lightweight-charts';
import { type Bar } from './datafeed/datafeed-core';
import { toChartTime } from './datafeed/timestamp';

/** Instrument render hints for the price format (from the instrument master; sensible defaults). */
export interface PriceFormat {
  precision: number;
  minMove: number;
}

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

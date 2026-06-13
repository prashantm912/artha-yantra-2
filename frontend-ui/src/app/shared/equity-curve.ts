import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  viewChild,
} from '@angular/core';
import {
  createChart,
  LineSeries,
  type IChartApi,
  type ISeriesApi,
  type LineData,
  type UTCTimestamp,
} from 'lightweight-charts';

/** A curve point as the backtest results deliver it (`ts` ISO, `value` decimal string). */
export interface CurvePoint {
  ts: string;
  value: string;
}

/**
 * Shared lightweight-charts equity/drawdown curve wrapper (E-4 / E-9): a designated chart-wrapper,
 * so it may import lightweight-charts outside `/charts` (the E-9 lint boundary whitelists it).
 * Equity + optional benchmark share the right scale; drawdown rides a separate left scale.
 * Decimal-string→number conversion happens HERE, at the render boundary only. Guarded for jsdom.
 */
@Component({
  selector: 'ay-equity-curve',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .chart {
      width: 100%;
      height: 100%;
      min-height: 14rem;
    }
  `,
  template: `<div #host class="chart"></div>`,
})
export class EquityCurveComponent {
  readonly equity = input.required<CurvePoint[]>();
  readonly benchmark = input<CurvePoint[] | null>(null);
  readonly drawdown = input<CurvePoint[] | null>(null);

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private chart?: IChartApi;
  private equitySeries?: ISeriesApi<'Line'>;
  private benchmarkSeries?: ISeriesApi<'Line'>;
  private drawdownSeries?: ISeriesApi<'Line'>;
  private resize?: ResizeObserver;

  constructor() {
    afterNextRender(() => this.init());
    effect(() => {
      const eq = this.toLine(this.equity());
      this.equitySeries?.setData(eq);
      this.benchmarkSeries?.setData(this.toLine(this.benchmark() ?? []));
      this.drawdownSeries?.setData(this.toLine(this.drawdown() ?? []));
      this.chart?.timeScale().fitContent();
    });
    inject(DestroyRef).onDestroy(() => {
      this.resize?.disconnect();
      this.chart?.remove();
    });
  }

  /** ISO ts → epoch-seconds UTCTimestamp; decimal string → number (render boundary only). Deduped. */
  private toLine(points: CurvePoint[]): LineData<UTCTimestamp>[] {
    const byTime = new Map<number, number>();
    for (const p of points) {
      byTime.set(Math.floor(Date.parse(p.ts) / 1000), Number(p.value));
    }
    return [...byTime.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([time, value]) => ({ time: time as UTCTimestamp, value }));
  }

  private token(name: string, fallback: string): string {
    return getComputedStyle(this.host().nativeElement).getPropertyValue(name).trim() || fallback;
  }

  private init(): void {
    const el = this.host().nativeElement;
    try {
      const grid = this.token('--ay-chart-grid', '#1f2940');
      const text = this.token('--ay-text-muted', '#93a0bd');
      this.chart = createChart(el, {
        width: el.clientWidth || 320,
        height: el.clientHeight || 220,
        autoSize: false,
        layout: { background: { color: 'transparent' }, textColor: text, attributionLogo: false },
        grid: { vertLines: { color: grid }, horzLines: { color: grid } },
        rightPriceScale: { borderVisible: false },
        leftPriceScale: { visible: !!this.drawdown(), borderVisible: false },
        timeScale: { borderVisible: false },
      });
      this.equitySeries = this.chart.addSeries(LineSeries, {
        color: this.token('--ay-accent', '#38bdf8'),
        lineWidth: 2,
        priceScaleId: 'right',
        lastValueVisible: true,
      });
      this.benchmarkSeries = this.chart.addSeries(LineSeries, {
        color: this.token('--ay-text-muted', '#93a0bd'),
        lineWidth: 1,
        priceScaleId: 'right',
        lineStyle: 2,
        lastValueVisible: false,
      });
      this.drawdownSeries = this.chart.addSeries(LineSeries, {
        color: this.token('--ay-bear', '#ef4444'),
        lineWidth: 1,
        priceScaleId: 'left',
        lastValueVisible: false,
      });
      this.equitySeries.setData(this.toLine(this.equity()));
      this.benchmarkSeries.setData(this.toLine(this.benchmark() ?? []));
      this.drawdownSeries.setData(this.toLine(this.drawdown() ?? []));
      this.chart.timeScale().fitContent();
      this.resize = new ResizeObserver(() => {
        this.chart?.applyOptions({ width: el.clientWidth || 320, height: el.clientHeight || 220 });
      });
      this.resize.observe(el);
    } catch {
      // no real canvas (jsdom) — renders nothing
    }
  }
}

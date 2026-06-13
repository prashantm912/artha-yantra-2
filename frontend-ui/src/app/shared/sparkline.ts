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
  type UTCTimestamp,
} from 'lightweight-charts';

/** One sparkline point: epoch-SECONDS time + the value already converted at this render boundary. */
export interface SparkPoint {
  time: number;
  value: number;
}

/**
 * Shared lightweight-charts sparkline wrapper (E-4 / E-9): a designated chart-wrapper component, so
 * it legitimately imports lightweight-charts OUTSIDE `/charts` (the E-9 lint boundary whitelists it
 * alongside the equity-curve wrapper). Decimal-string→number conversion happens in the CALLER at
 * this render boundary; the wrapper only ever sees numbers. Guarded so jsdom (no real canvas) no-ops.
 */
@Component({
  selector: 'ay-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .spark {
      width: 100%;
      height: 100%;
      min-height: 36px;
    }
  `,
  template: `<div #host class="spark"></div>`,
})
export class SparklineComponent {
  readonly points = input.required<SparkPoint[]>();
  /** A resolved `--ay-*` token name to colour the line (bull/bear/accent). */
  readonly tone = input<'bull' | 'bear' | 'accent'>('accent');

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private chart?: IChartApi;
  private series?: ISeriesApi<'Line'>;
  private resize?: ResizeObserver;

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => this.init());
    effect(() => {
      const pts = this.points();
      this.series?.setData(pts as { time: UTCTimestamp; value: number }[]);
      this.chart?.timeScale().fitContent();
    });
    destroyRef.onDestroy(() => {
      this.resize?.disconnect();
      this.chart?.remove();
    });
  }

  private init(): void {
    const el = this.host().nativeElement;
    try {
      const css = getComputedStyle(el);
      const token =
        this.tone() === 'bull' ? '--ay-bull' : this.tone() === 'bear' ? '--ay-bear' : '--ay-accent';
      const color = css.getPropertyValue(token).trim() || '#38bdf8';
      this.chart = createChart(el, {
        width: el.clientWidth || 120,
        height: el.clientHeight || 40,
        autoSize: false,
        layout: {
          background: { color: 'transparent' },
          textColor: 'transparent',
          attributionLogo: false,
        },
        rightPriceScale: { visible: false },
        leftPriceScale: { visible: false },
        timeScale: { visible: false, borderVisible: false },
        grid: { vertLines: { visible: false }, horzLines: { visible: false } },
        crosshair: {
          horzLine: { visible: false, labelVisible: false },
          vertLine: { visible: false, labelVisible: false },
        },
        handleScroll: false,
        handleScale: false,
      });
      this.series = this.chart.addSeries(LineSeries, {
        color,
        lineWidth: 2,
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: false,
      });
      this.series.setData(this.points() as { time: UTCTimestamp; value: number }[]);
      this.chart.timeScale().fitContent();
      this.resize = new ResizeObserver(() => {
        if (this.chart) {
          this.chart.applyOptions({ width: el.clientWidth || 120, height: el.clientHeight || 40 });
          this.chart.timeScale().fitContent();
        }
      });
      this.resize.observe(el);
    } catch {
      // no real canvas (jsdom / unsupported env) — the sparkline simply renders nothing
    }
  }
}

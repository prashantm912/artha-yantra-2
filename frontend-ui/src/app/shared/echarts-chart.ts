import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import type { EChartsCoreOption, ECharts } from 'echarts/core';
import { echarts } from './echarts-bootstrap';

/**
 * Generic ECharts wrapper (D4): the caller supplies a full option; the wrapper owns init/resize/
 * dispose and merges a transparent, token-coloured base. `aria.enabled` is on by default (E-7).
 * Guarded for jsdom. ECharts theming re-renders on theme switch is out of scope for v1 (option is
 * re-applied on data changes).
 */
@Component({
  selector: 'ay-echart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .chart {
      width: 100%;
      height: 100%;
      min-height: 16rem;
    }
  `,
  template: `<div #host class="chart"></div>`,
})
export class EChartsComponent {
  readonly option = input.required<EChartsCoreOption>();
  /** Emits the ECharts instance after init so callers can attach events (e.g. brush filtering). */
  readonly chartInit = output<ECharts>();

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private chart?: ECharts;
  private resize?: ResizeObserver;

  constructor() {
    afterNextRender(() => this.init());
    effect(() => {
      const opt = this.option();
      this.chart?.setOption(this.withBase(opt), true);
    });
    inject(DestroyRef).onDestroy(() => {
      this.resize?.disconnect();
      try {
        this.chart?.dispose();
      } catch {
        // chart was never fully initialised (e.g. jsdom has no 2d canvas context)
      }
    });
  }

  private withBase(option: EChartsCoreOption): EChartsCoreOption {
    const el = this.host().nativeElement;
    const text = getComputedStyle(el).getPropertyValue('--ay-text-muted').trim() || '#93a0bd';
    return {
      backgroundColor: 'transparent',
      textStyle: { color: text },
      aria: { enabled: true },
      ...option,
    };
  }

  private init(): void {
    const el = this.host().nativeElement;
    try {
      this.chart = echarts.init(el, undefined, { renderer: 'canvas' });
      this.chart.setOption(this.withBase(this.option()), true);
      this.chartInit.emit(this.chart);
      this.resize = new ResizeObserver(() => {
        try {
          this.chart?.resize();
        } catch {
          // resize fired during teardown / headless — ignore
        }
      });
      this.resize.observe(el);
    } catch {
      // no real canvas (jsdom) — renders nothing
    }
  }
}

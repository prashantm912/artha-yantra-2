import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { LwcChartComponent } from './lwc-chart.component';

/**
 * /charts (Phase 40, A13): the lightweight-charts main chart. The first-party toolbar (instrument
 * search, interval picker, overlays) lands in Phase 40C and trade/signal marks in 40A; this phase
 * ships the chart itself + the datafeed core + the containment boundary. Reads `?symbol=&interval=`
 * so deep links (Phase 40A) center the chart.
 */
@Component({
  selector: 'ay-charts-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LwcChartComponent],
  styles: `
    .wrap {
      height: calc(100vh - 6rem);
    }
  `,
  template: `
    <h1 class="ay-sr-only">Charts</h1>
    <div class="wrap">
      <ay-lwc-chart [symbol]="symbol()" [interval]="interval()" />
    </div>
  `,
})
export class ChartsPage {
  private readonly route = inject(ActivatedRoute);
  protected readonly symbol = signal('NSE:NIFTY 50');
  protected readonly interval = signal('1d');

  constructor() {
    this.route.queryParamMap.subscribe((q) => {
      this.symbol.set(q.get('symbol') ?? 'NSE:NIFTY 50');
      this.interval.set(q.get('interval') ?? '1d');
    });
  }
}

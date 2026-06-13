import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ChartStateStore } from './chart-state.store';
import { ChartToolbar } from './chart-toolbar';
import { LwcChartComponent } from './lwc-chart.component';

/**
 * /charts (Phases 40 / 40C, A13): lightweight-charts main chart + first-party toolbar (interval,
 * instrument search, engine overlays) with localStorage-persisted chart state. Deep links
 * (`?symbol=&interval=`, Phase 40A) override the persisted symbol/interval on load.
 */
@Component({
  selector: 'ay-charts-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ChartToolbar, LwcChartComponent],
  styles: `
    .wrap {
      height: calc(100vh - 9rem);
    }
  `,
  template: `
    <h1 class="ay-sr-only">Charts</h1>
    <ay-chart-toolbar [(showTable)]="showTable" />
    <div class="wrap">
      <ay-lwc-chart
        [symbol]="state.symbol()"
        [interval]="state.interval()"
        [overlays]="state.overlays()"
        [showTable]="showTable()"
      />
    </div>
  `,
})
export class ChartsPage {
  protected readonly state = inject(ChartStateStore);
  protected readonly showTable = signal(false);
  private readonly route = inject(ActivatedRoute);

  constructor() {
    this.route.queryParamMap.subscribe((q) => {
      const symbol = q.get('symbol');
      const interval = q.get('interval');
      if (symbol) {
        this.state.setSymbol(symbol);
      }
      if (interval) {
        this.state.setInterval(interval);
      }
    });
  }
}

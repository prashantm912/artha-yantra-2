import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TableModule } from 'primeng/table';
import type { EChartsCoreOption } from 'echarts/core';
import { formatDecimal } from '../../core/decimal';
import { BreadthStore } from '../../stores/breadth.store';
import { EChartsComponent } from '../../shared/echarts-chart';

/**
 * Market breadth (oipulse parity): advance/decline counts + delivery%-leaders from the NSE EQ
 * bhavcopy for a date. Date-driven (own picker); reads /market/breadth via {@link BreadthStore}.
 */
@Component({
  selector: 'ay-breadth-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, TableModule, EChartsComponent],
  styles: `
    .bar {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      margin-bottom: 0.8rem;
    }
    .date {
      height: 2.5rem;
      padding: 0 0.6rem;
      border: 1px solid var(--ay-border);
      border-radius: 6px;
      background: var(--ay-surface-1);
      color: var(--ay-text);
    }
    .summary {
      margin: 0 0 0.8rem;
      color: var(--ay-text-muted);
      font-variant-numeric: tabular-nums;
    }
    .up {
      color: var(--ay-bull);
    }
    .down {
      color: var(--ay-bear);
    }
    .chart {
      height: 12rem;
      margin-bottom: 1rem;
    }
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Market breadth</h1>
    <div class="bar">
      <label for="breadth-date">Date</label>
      <input
        id="breadth-date"
        class="date"
        type="date"
        [ngModel]="store.date()"
        (ngModelChange)="store.setDate($event)"
      />
    </div>

    @if (store.breadth(); as b) {
      <p class="summary" aria-live="polite">
        <span class="up">▲ {{ b.summary.advances }} advances</span> ·
        <span class="down">▼ {{ b.summary.declines }} declines</span> ·
        {{ b.summary.unchanged }} unchanged · {{ b.summary.total }} EQ symbols · avg delivery
        {{ dec(b.summary.avgDeliveryPct) }}%
      </p>

      <div class="chart"><ay-echart [option]="breadthChart()" /></div>

      <p-table
        [value]="b.topDelivery"
        [scrollable]="true"
        scrollHeight="58vh"
        dataKey="symbol"
        [loading]="store.loading()"
      >
        <ng-template #header>
          <tr>
            <th>Symbol</th>
            <th class="num">Delivery %</th>
            <th class="num">Close</th>
            <th class="num">% change</th>
          </tr>
        </ng-template>
        <ng-template #body let-r>
          <tr>
            <td>{{ r.symbol }}</td>
            <td class="num">{{ dec(r.deliveryPct) }}</td>
            <td class="num">{{ dec(r.close) }}</td>
            <td class="num">{{ dec(r.pctChange) }}</td>
          </tr>
        </ng-template>
        <ng-template #emptymessage>
          <tr>
            <td colspan="4">No delivery leaders for this date.</td>
          </tr>
        </ng-template>
      </p-table>
    } @else {
      <p class="summary">No bhavcopy ingested for this date.</p>
    }
  `,
})
export class BreadthPage {
  protected readonly store = inject(BreadthStore);

  private readonly reload = effect(() => {
    this.store.date();
    this.store.load();
  });

  /** Advances / declines / unchanged bar (colour reinforces, the axis labels carry the meaning). */
  protected readonly breadthChart = computed<EChartsCoreOption>(() => {
    const s = this.store.breadth()?.summary;
    if (!s) {
      return {};
    }
    return {
      tooltip: {},
      grid: { left: 44, right: 16, top: 16, bottom: 28 },
      xAxis: { type: 'category', data: ['Advances', 'Declines', 'Unchanged'] },
      yAxis: { type: 'value' },
      series: [
        {
          type: 'bar',
          data: [
            { value: s.advances, itemStyle: { color: '#22c55e' } },
            { value: s.declines, itemStyle: { color: '#ef4444' } },
            { value: s.unchanged, itemStyle: { color: '#93a0bd' } },
          ],
        },
      ],
    };
  });

  protected dec(value: string | null | undefined): string {
    return value ? formatDecimal(value, 2) : '—';
  }
}

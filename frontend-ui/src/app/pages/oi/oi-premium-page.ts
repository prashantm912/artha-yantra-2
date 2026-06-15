import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { TableModule } from 'primeng/table';
import type { EChartsCoreOption } from 'echarts/core';
import { formatDecimal } from '../../core/decimal';
import { OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { SymbolContextStore } from '../../stores/symbol-context.store';
import { EChartsComponent } from '../../shared/echarts-chart';
import { OiControlBar } from './oi-control-bar';

/**
 * Option Premium (oipulse parity): the per-strike straddle/strangle premium chain (CE + PE per
 * strike) with the ATM straddle in the header, plus the intraday ATM-straddle decay chart behind
 * GET /options/premium-series. Reads /options/premium + /premium-series via {@link OiAnalyticsStore},
 * reloading on the shared {@link SymbolContextStore} selection.
 */
@Component({
  selector: 'ay-oi-premium-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, EChartsComponent, OiControlBar],
  styles: `
    .meta {
      margin: 0 0 0.7rem;
      color: var(--ay-text-muted);
      font-variant-numeric: tabular-nums;
    }
    .chart {
      height: 14rem;
      margin-bottom: 1rem;
    }
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Option premium</h1>
    <ay-oi-control-bar />

    <p class="meta" aria-live="polite">
      @if (store.premium(); as p) {
        @if (p.atmStraddle) {
          ATM straddle {{ dec(p.atmStraddle, 2) }} &#64; {{ dec(p.atmStrike, 0) }}
          @if (p.spot) {
            · spot {{ dec(p.spot, 2) }}
          }
        } @else {
          No ATM straddle yet.
        }
      }
    </p>

    @if ((store.premiumSeries()?.items?.length ?? 0) > 1) {
      <div class="chart"><ay-echart [option]="decayChart()" /></div>
    }

    <p-table
      [value]="store.premium()?.items ?? []"
      [scrollable]="true"
      scrollHeight="58vh"
      dataKey="strike"
    >
      <ng-template #header>
        <tr>
          <th>Strike</th>
          <th class="num">CE</th>
          <th class="num">PE</th>
          <th class="num">Straddle</th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr>
          <td class="num">{{ dec(row.strike, 0) }}</td>
          <td class="num">{{ dec(row.ce, 2) }}</td>
          <td class="num">{{ dec(row.pe, 2) }}</td>
          <td class="num">{{ dec(row.straddle, 2) }}</td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="4">No premium chain for this selection.</td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class OiPremiumPage {
  protected readonly store = inject(OiAnalyticsStore);
  private readonly ctx = inject(SymbolContextStore);

  private readonly reload = effect(() => {
    this.ctx.name();
    this.ctx.expiry();
    this.ctx.interval();
    this.ctx.mode();
    this.ctx.date();
    this.store.loadPremium();
    this.store.loadPremiumSeries();
  });

  /** ATM-straddle decay over the bucket series (Number only at the chart boundary). */
  protected readonly decayChart = computed<EChartsCoreOption>(() => {
    const items = this.store.premiumSeries()?.items ?? [];
    return {
      tooltip: { trigger: 'axis' },
      grid: { left: 64, right: 16, top: 16, bottom: 28 },
      xAxis: { type: 'category', data: items.map((p) => p.bucket.slice(11, 16)) },
      yAxis: { type: 'value', name: 'ATM straddle' },
      series: [
        {
          name: 'ATM straddle',
          type: 'line',
          smooth: true,
          data: items.map((p) => (p.atmStraddle == null ? null : Number(p.atmStraddle))),
        },
      ],
    };
  });

  protected dec(value: string | null | undefined, fractionDigits: number): string {
    return value ? formatDecimal(value, fractionDigits) : '—';
  }
}

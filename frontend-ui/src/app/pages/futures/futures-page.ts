import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { EChartsCoreOption } from 'echarts/core';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ButtonModule } from 'primeng/button';
import { formatDecimal } from '../../core/decimal';
import { EChartsComponent } from '../../shared/echarts-chart';
import { FuturesStore, stateSeverity } from '../../stores/futures.store';
import { MarketStore } from '../../stores/market.store';

/**
 * /futures (F-42A): the futures workbench — near/next/far term-structure cards (basis +
 * contango/backwardation), the FUT−spot basis-history chart, a calendar-spread/rollover-cost
 * panel and OI-buildup heat tiles consuming the `oi_buildup` screener preset (classification stays
 * server-side). Same store/ECharts posture as /options; REST-only, never the tick firehose.
 */
@Component({
  selector: 'ay-futures-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, SelectModule, TableModule, TagModule, ButtonModule, EChartsComponent],
  styles: `
    .filters {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      margin-bottom: 0.8rem;
    }
    .spacer {
      flex: 1;
    }
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
      gap: 0.8rem;
      margin-bottom: 1rem;
    }
    .card {
      border: 1px solid var(--ay-border);
      border-radius: 10px;
      background: var(--ay-surface-1);
      padding: 0.8rem;
    }
    .card h3 {
      margin: 0 0 0.4rem;
      font-size: 0.95rem;
    }
    .row {
      display: flex;
      justify-content: space-between;
      font-variant-numeric: tabular-nums;
      font-size: 0.85rem;
      padding: 0.1rem 0;
    }
    .row .k {
      color: var(--ay-text-muted);
    }
    .grid2 {
      display: grid;
      grid-template-columns: 1.6fr 1fr;
      gap: 1rem;
      align-items: start;
    }
    .panel {
      border: 1px solid var(--ay-border);
      border-radius: 10px;
      background: var(--ay-surface-1);
      padding: 0.9rem;
    }
    .chart {
      height: 18rem;
    }
    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
      gap: 0.7rem;
      margin-top: 1rem;
    }
    .tile {
      border: 1px solid var(--ay-border);
      border-radius: 10px;
      padding: 0.7rem;
      background: var(--ay-surface-1);
    }
    .tile h4 {
      margin: 0 0 0.3rem;
      font-size: 0.85rem;
    }
    .tile .count {
      font-size: 1.4rem;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }
    .tile ul {
      margin: 0.3rem 0 0;
      padding-left: 1rem;
      font-size: 0.78rem;
      color: var(--ay-text-muted);
    }
  `,
  template: `
    <h1 class="ay-sr-only">Futures workbench</h1>
    <div class="filters">
      <p-select
        [options]="underlyings()"
        [ngModel]="store.underlying()"
        (ngModelChange)="store.setUnderlying($event)"
        [filter]="true"
        ariaLabel="Underlying"
      />
      @if (store.term(); as t) {
        <p-tag [severity]="severity(t.state)" [value]="t.state" />
      }
      <p-button [text]="true" icon="pi pi-refresh" ariaLabel="Reload" (onClick)="store.load()" />
      <span class="spacer"></span>
      @if (store.stale()) {
        <p-tag severity="warn" value="STALE" pTooltip="Market closed — last good structure" />
      }
    </div>

    @if (store.term(); as t) {
      <div class="cards">
        @for (leg of t.contracts; track leg.tradingsymbol; let i = $index) {
          <div class="card">
            <h3>{{ tenor(i) }} · {{ leg.tradingsymbol }}</h3>
            <div class="row">
              <span class="k">LTP</span><span>{{ price(leg.ltp) }}</span>
            </div>
            <div class="row">
              <span class="k">Basis</span><span>{{ price(leg.basisAbsolute) }}</span>
            </div>
            <div class="row">
              <span class="k">Annualized</span><span>{{ pct(leg.basisAnnualized) }}</span>
            </div>
            <div class="row">
              <span class="k">Days to expiry</span><span>{{ leg.daysToExpiry }}</span>
            </div>
            <div class="row">
              <span class="k">OI</span><span>{{ leg.oi ?? '—' }}</span>
            </div>
          </div>
        }
      </div>

      <div class="grid2">
        <div class="panel">
          <h3>Basis history (FUT − spot)</h3>
          <div class="chart"><ay-echart [option]="basisOption()" /></div>
        </div>
        <div class="panel">
          <h3>Calendar spread / rollover</h3>
          <div class="row">
            <span class="k">Spot</span><span>{{ price(t.spot) }}</span>
          </div>
          <div class="row">
            <span class="k">Next − front</span>
            <span>{{ t.calendarSpread ? price(t.calendarSpread) : '—' }}</span>
          </div>
          <div class="row">
            <span class="k">Roll % of spot</span
            ><span>{{ rollPct(t.calendarSpread, t.spot) }}</span>
          </div>
          <div class="row">
            <span class="k">State</span><span>{{ t.state }}</span>
          </div>
        </div>
      </div>
    } @else {
      <p class="meta">No FUT contracts for this underlying.</p>
    }

    <div class="tiles">
      @for (tile of store.buildupTiles(); track tile.key) {
        <div class="tile">
          <h4>{{ tile.title }}</h4>
          <div class="count">{{ tile.rows.length }}</div>
          <ul>
            @for (row of tile.rows.slice(0, 5); track row.tradingsymbol) {
              <li>{{ row.tradingsymbol }}</li>
            }
          </ul>
        </div>
      }
    </div>
  `,
})
export class FuturesPage {
  protected readonly store = inject(FuturesStore);
  private readonly market = inject(MarketStore);

  /** All synced F&O underlyings (NSE/BSE), falling back to the index defaults until loaded. */
  protected readonly underlyings = computed(() =>
    this.market.underlyings().length ? this.market.underlyings() : ['NIFTY 50', 'NIFTY BANK'],
  );

  constructor() {
    this.market.loadUnderlyings();
  }

  protected readonly basisOption = computed<EChartsCoreOption>(() => {
    const series = this.store.basisSeries();
    return {
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: series.map((p) => p.bucket.slice(0, 10)), name: 'date' },
      yAxis: { type: 'value', name: 'basis' },
      series: [
        { name: 'basis', type: 'line', smooth: true, data: series.map((p) => Number(p.basis)) },
      ],
    };
  });

  protected severity(state: string): 'info' | 'warn' {
    return stateSeverity(state);
  }

  protected tenor(index: number): string {
    return ['Front', 'Next', 'Far'][index] ?? `M${index + 1}`;
  }

  protected price(value: string): string {
    return formatDecimal(value, 2);
  }

  protected pct(value: string): string {
    return `${formatDecimal(value, 2)}%`;
  }

  /** Calendar spread as a percentage of spot (a display ratio, not exact-decimal money). */
  protected rollPct(spread: string | null, spot: string): string {
    if (!spread || Number(spot) === 0) {
      return '—';
    }
    return `${((Number(spread) / Number(spot)) * 100).toFixed(2)}%`;
  }
}

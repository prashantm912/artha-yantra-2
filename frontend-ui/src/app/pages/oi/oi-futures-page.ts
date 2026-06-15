import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { TableModule } from 'primeng/table';
import type { EChartsCoreOption } from 'echarts/core';
import { formatDecimal } from '../../core/decimal';
import { OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { SymbolContextStore } from '../../stores/symbol-context.store';
import { DataBar } from '../../shared/data-bar';
import { EChartsComponent } from '../../shared/echarts-chart';
import { OiIntBadge } from '../../shared/oi-int-badge';
import { OiControlBar } from './oi-control-bar';

/**
 * Futures OI (oipulse parity): the per-contract OI table plus the Stage-G analytics sections —
 * interval spurt, gainers/losers movers, the index term-structure (banks) and the daily EOD
 * rollup. Reads the /futures/* endpoints via {@link OiAnalyticsStore}, reloading on the shared
 * {@link SymbolContextStore} selection (expiry ignored — the control bar hides it). Decimals are
 * strings. The buzz heatmap is rendered by a chart panel.
 */
@Component({
  selector: 'ay-oi-futures-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, DataBar, EChartsComponent, OiIntBadge, OiControlBar],
  styles: `
    .meta {
      margin: 0 0 0.7rem;
      color: var(--ay-text-muted);
      font-variant-numeric: tabular-nums;
    }
    h2 {
      font-size: 1rem;
      margin: 1.2rem 0 0.4rem;
      color: var(--ay-text);
    }
    .chart {
      height: 14rem;
    }
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Futures OI analysis</h1>
    <ay-oi-control-bar [showExpiry]="false" />

    <p class="meta" aria-live="polite">
      {{ store.futures().length }} contract{{ store.futures().length === 1 ? '' : 's' }} · snapshots
      accrue during market hours
    </p>

    <p-table
      [value]="store.futures()"
      [scrollable]="true"
      scrollHeight="34vh"
      [loading]="store.loadingFutures()"
      dataKey="tradingsymbol"
    >
      <ng-template #header>
        <tr>
          <th>Contract</th>
          <th class="num">LTP</th>
          <th class="num">OI</th>
          <th class="num">Δ OI</th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr>
          <td>{{ row.tradingsymbol }}</td>
          <td class="num">{{ dec(row.ltp, 2) }}</td>
          <td>
            <ay-data-bar [value]="row.oi ?? 0" [max]="store.maxFuturesOi()" [label]="oi(row.oi)" />
          </td>
          <td>
            <ay-data-bar
              [value]="row.oiChange ?? 0"
              [max]="store.maxFuturesOiChange()"
              [label]="signedOi(row.oiChange)"
              [tone]="oiTone(row.oiChange)"
            />
          </td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="4">No futures OI snapshots for this selection.</td>
        </tr>
      </ng-template>
    </p-table>

    <h2>Interval spurt</h2>
    <p-table [value]="store.futSpurt()?.items ?? []" [scrollable]="true" scrollHeight="22vh">
      <ng-template #header>
        <tr>
          <th>Contract</th>
          <th class="num">LTP</th>
          <th class="num">Δ OI</th>
          <th class="num">Spurt %</th>
          <th>Buildup</th>
        </tr>
      </ng-template>
      <ng-template #body let-r>
        <tr>
          <td>{{ r.tradingsymbol }}</td>
          <td class="num">{{ dec(r.ltp, 2) }}</td>
          <td class="num">{{ signedOi(r.oiChange) }}</td>
          <td class="num">{{ dec(r.spurtPct, 2) }}</td>
          <td><ay-oi-int-badge [value]="r.interpretation" /></td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="5">No interval spurt yet (needs two buckets).</td>
        </tr>
      </ng-template>
    </p-table>

    <h2>Movers</h2>
    <p-table [value]="movers()" [scrollable]="true" scrollHeight="22vh">
      <ng-template #header>
        <tr>
          <th>Contract</th>
          <th class="num">LTP</th>
          <th class="num">Price %</th>
          <th class="num">OI %</th>
          <th>Buildup</th>
        </tr>
      </ng-template>
      <ng-template #body let-r>
        <tr>
          <td>{{ r.tradingsymbol }}</td>
          <td class="num">{{ dec(r.ltp, 2) }}</td>
          <td class="num">{{ dec(r.pricePct, 2) }}</td>
          <td class="num">{{ dec(r.oiPct, 2) }}</td>
          <td><ay-oi-int-badge [value]="r.interpretation" /></td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="5">No movers for this selection.</td>
        </tr>
      </ng-template>
    </p-table>

    @if ((store.buzz()?.buckets?.length ?? 0) > 0) {
      <h2>Buzz (OI interpretation over time)</h2>
      <div class="chart"><ay-echart [option]="buzzChart()" /></div>
    }

    <h2>Term structure</h2>
    <p-table [value]="store.banks()?.items ?? []" [scrollable]="true" scrollHeight="20vh">
      <ng-template #header>
        <tr>
          <th>Contract</th>
          <th>Expiry</th>
          <th class="num">LTP</th>
          <th class="num">Basis</th>
          <th class="num">Δ OI</th>
          <th>Buildup</th>
        </tr>
      </ng-template>
      <ng-template #body let-r>
        <tr>
          <td>{{ r.tradingsymbol }}</td>
          <td>{{ r.expiry ?? '—' }}</td>
          <td class="num">{{ dec(r.ltp, 2) }}</td>
          <td class="num">{{ dec(r.basis, 2) }}</td>
          <td class="num">{{ signedOi(r.oiChange) }}</td>
          <td><ay-oi-int-badge [value]="r.interpretation" /></td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="6">No contracts for this selection.</td>
        </tr>
      </ng-template>
    </p-table>

    <h2>EOD ({{ eodDate() }})</h2>
    <p-table
      [value]="store.eod()"
      [scrollable]="true"
      scrollHeight="20vh"
      [loading]="store.loadingEod()"
    >
      <ng-template #header>
        <tr>
          <th>Contract</th>
          <th class="num">O</th>
          <th class="num">H</th>
          <th class="num">L</th>
          <th class="num">C</th>
          <th class="num">OI close</th>
          <th class="num">Δ OI</th>
        </tr>
      </ng-template>
      <ng-template #body let-r>
        <tr>
          <td>{{ r.tradingsymbol }}</td>
          <td class="num">{{ dec(r.open, 2) }}</td>
          <td class="num">{{ dec(r.high, 2) }}</td>
          <td class="num">{{ dec(r.low, 2) }}</td>
          <td class="num">{{ dec(r.close, 2) }}</td>
          <td class="num">{{ oi(r.oiClose) }}</td>
          <td class="num">{{ signedOi(r.oiChange) }}</td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="7">No EOD rollup for this date (forward-only OHLC capture).</td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class OiFuturesPage {
  protected readonly store = inject(OiAnalyticsStore);
  private readonly ctx = inject(SymbolContextStore);

  private readonly reload = effect(() => {
    this.ctx.name();
    this.ctx.interval();
    this.ctx.mode();
    this.ctx.date();
    this.store.loadFutures();
    this.store.loadFutSpurt();
    this.store.loadMovers();
    this.store.loadBanks();
    this.store.loadBuzz();
    this.store.loadEod(this.eodDate());
  });

  /** Movers as a single gainers-then-losers list for the table. */
  protected movers() {
    const m = this.store.movers();
    return m ? [...m.gainers, ...m.losers] : [];
  }

  /** Buzz heatmap: bucket (x) x contract (y), the 4-state encoded 0..3 with a labelled legend. */
  protected readonly buzzChart = computed<EChartsCoreOption>(() => {
    const m = this.store.buzz();
    if (!m) {
      return {};
    }
    const order = ['LONG_BUILDUP', 'SHORT_BUILDUP', 'SHORT_COVERING', 'LONG_UNWINDING'];
    const data: [number, number, number][] = [];
    m.cells.forEach((row, bi) =>
      row.forEach((cell, ci) => {
        if (cell != null) {
          data.push([bi, ci, order.indexOf(cell)]);
        }
      }),
    );
    return {
      tooltip: { position: 'top' },
      grid: { left: 120, right: 16, top: 12, bottom: 56 },
      xAxis: { type: 'category', data: m.buckets.map((b) => b.slice(11, 16)) },
      yAxis: { type: 'category', data: m.contracts },
      visualMap: {
        type: 'piecewise',
        orient: 'horizontal',
        bottom: 0,
        pieces: [
          { value: 0, label: 'Long buildup', color: '#22c55e' },
          { value: 1, label: 'Short buildup', color: '#ef4444' },
          { value: 2, label: 'Short covering', color: '#3b82f6' },
          { value: 3, label: 'Long unwinding', color: '#f59e0b' },
        ],
      },
      series: [{ type: 'heatmap', data, label: { show: false } }],
    };
  });

  /** EOD date: the history-mode date if chosen, else today (IST). */
  protected eodDate(): string {
    return (
      this.ctx.date() ??
      new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date())
    );
  }

  protected dec(value: string | null | undefined, fractionDigits: number): string {
    return value ? formatDecimal(value, fractionDigits) : '—';
  }

  protected oi(value: number | null | undefined): string {
    return value != null ? value.toLocaleString('en-IN') : '—';
  }

  protected signedOi(value: number | null | undefined): string {
    if (value == null) {
      return '—';
    }
    return value > 0 ? '+' + value.toLocaleString('en-IN') : value.toLocaleString('en-IN');
  }

  protected oiTone(value: number | null | undefined): 'bull' | 'bear' | 'neutral' {
    if (value == null || value === 0) {
      return 'neutral';
    }
    return value > 0 ? 'bull' : 'bear';
  }
}

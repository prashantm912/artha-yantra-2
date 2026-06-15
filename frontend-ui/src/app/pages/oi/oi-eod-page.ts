import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TableModule } from 'primeng/table';
import type { EChartsCoreOption } from 'echarts/core';
import { formatDecimal } from '../../core/decimal';
import { type EodRow, OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { SymbolContextStore } from '../../stores/symbol-context.store';
import { EChartsComponent } from '../../shared/echarts-chart';
import { OiControlBar } from './oi-control-bar';

/**
 * Futures EOD OI Analyzer (oipulse parity): the daily OHLC + OI-close rollup behind GET
 * /api/v1/market/futures/eod over a [from, to] date range, with an OHLC candlestick of the
 * front (most-captured) contract. Forward-only OHLC — pre-V015 days show no candle. The
 * underlying is the shared {@link SymbolContextStore} name; the range is this page's own picker.
 */
@Component({
  selector: 'ay-oi-eod-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, TableModule, EChartsComponent, OiControlBar],
  styles: `
    .range {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      margin: 0.6rem 0 0.8rem;
    }
    .range input {
      height: 2.4rem;
      padding: 0 0.6rem;
      border: 1px solid var(--ay-border);
      border-radius: 6px;
      background: var(--ay-surface-1);
      color: var(--ay-text);
    }
    .chart {
      height: 15rem;
      margin-bottom: 1rem;
    }
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Futures EOD OI analyzer</h1>
    <ay-oi-control-bar />

    <div class="range">
      <label for="eod-from">From</label>
      <input id="eod-from" type="date" [ngModel]="from()" (ngModelChange)="from.set($event)" />
      <label for="eod-to">To</label>
      <input id="eod-to" type="date" [ngModel]="to()" (ngModelChange)="to.set($event)" />
    </div>

    @if (frontRows().length > 1) {
      <div class="chart"><ay-echart [option]="ohlcChart()" /></div>
    }

    <p-table
      [value]="store.eod()"
      [scrollable]="true"
      scrollHeight="54vh"
      [loading]="store.loadingEod()"
    >
      <ng-template #header>
        <tr>
          <th>Contract</th>
          <th>Date</th>
          <th class="num">O</th>
          <th class="num">H</th>
          <th class="num">L</th>
          <th class="num">C</th>
          <th class="num">OI close</th>
          <th class="num">Δ OI</th>
          <th class="num">Volume</th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr>
          <td>{{ row.tradingsymbol }}</td>
          <td>{{ row.tradeDate }}</td>
          <td class="num">{{ dec(row.open, 2) }}</td>
          <td class="num">{{ dec(row.high, 2) }}</td>
          <td class="num">{{ dec(row.low, 2) }}</td>
          <td class="num">{{ dec(row.close, 2) }}</td>
          <td class="num">{{ num(row.oiClose) }}</td>
          <td class="num">{{ signed(row.oiChange) }}</td>
          <td class="num">{{ num(row.volume) }}</td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="9">No EOD rollup for this range (forward-only OHLC capture).</td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class OiEodPage {
  protected readonly store = inject(OiAnalyticsStore);
  private readonly ctx = inject(SymbolContextStore);

  protected readonly from = signal(isoDaysAgo(14));
  protected readonly to = signal(isoDaysAgo(0));

  private readonly reload = effect(() => {
    this.ctx.name();
    const from = this.from();
    const to = this.to();
    this.store.loadEod(from, to);
  });

  /** The most-captured (front) contract's rows, date-sorted — the candlestick subject. */
  protected readonly frontRows = computed<EodRow[]>(() => {
    const byContract = new Map<string, EodRow[]>();
    for (const r of this.store.eod()) {
      const list = byContract.get(r.tradingsymbol) ?? [];
      list.push(r);
      byContract.set(r.tradingsymbol, list);
    }
    let best: EodRow[] = [];
    for (const list of byContract.values()) {
      if (list.length > best.length) {
        best = list;
      }
    }
    return [...best].sort((a, b) => a.tradeDate.localeCompare(b.tradeDate));
  });

  /** Front-contract OHLC candlestick (Number only at the chart boundary; null OHLC → gap). */
  protected readonly ohlcChart = computed<EChartsCoreOption>(() => {
    const rows = this.frontRows();
    const candle = (r: EodRow): (number | string)[] =>
      [r.open, r.close, r.low, r.high].map((v) => (v == null ? '-' : Number(v)));
    return {
      tooltip: { trigger: 'axis' },
      grid: { left: 64, right: 16, top: 16, bottom: 28 },
      xAxis: { type: 'category', data: rows.map((r) => r.tradeDate) },
      yAxis: { type: 'value', scale: true },
      series: [{ name: rows[0]?.tradingsymbol ?? '', type: 'candlestick', data: rows.map(candle) }],
    };
  });

  protected dec(value: string | null | undefined, fractionDigits: number): string {
    return value ? formatDecimal(value, fractionDigits) : '—';
  }

  protected num(value: number | null | undefined): string {
    return value != null ? value.toLocaleString('en-IN') : '—';
  }

  protected signed(value: number | null | undefined): string {
    if (value == null) {
      return '—';
    }
    return value > 0 ? '+' + value.toLocaleString('en-IN') : value.toLocaleString('en-IN');
  }
}

/** ISO yyyy-mm-dd `n` days before today (the EOD range picker defaults). */
function isoDaysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

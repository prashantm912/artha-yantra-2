import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { TableModule } from 'primeng/table';
import { formatDecimal } from '../../core/decimal';
import { OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { SymbolContextStore } from '../../stores/symbol-context.store';
import { DataBar } from '../../shared/data-bar';
import { OiIntBadge } from '../../shared/oi-int-badge';
import { OiControlBar } from './oi-control-bar';

/**
 * Bank-stock futures grid (oipulse "Banks Analysis"): one row per bank-sector stock = its FRONT
 * future's buildup behind GET /api/v1/market/futures/banks-grid. Sector-wide — no symbol selector
 * (the control bar hides Name/Expiry); reloads on mode/date/interval only. Forward-only capture, so
 * the grid is empty until two snapshot buckets accrue.
 */
@Component({
  selector: 'ay-oi-bank-grid-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, DataBar, OiIntBadge, OiControlBar],
  styles: `
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .bull {
      color: var(--ay-bull);
    }
    .bear {
      color: var(--ay-bear);
    }
  `,
  template: `
    <h1 class="ay-sr-only">Bank-stock futures grid</h1>
    <ay-oi-control-bar [showName]="false" [showExpiry]="false" />

    <p-table
      [value]="store.bankGrid()?.items ?? []"
      [scrollable]="true"
      scrollHeight="64vh"
      [loading]="store.loadingBankGrid()"
      dataKey="underlying"
    >
      <ng-template #header>
        <tr>
          <th>Stock</th>
          <th>Contract</th>
          <th class="num">LTP</th>
          <th class="num">Price %</th>
          <th class="num">OI</th>
          <th class="num">Δ OI</th>
          <th class="num">OI %</th>
          <th>Buildup</th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr>
          <td>{{ row.underlying }}</td>
          <td>{{ row.tradingsymbol }}</td>
          <td class="num">{{ dec(row.ltp, 2) }}</td>
          <td class="num" [class]="pctClass(row.pricePct)">{{ pct(row.pricePct) }}</td>
          <td><ay-data-bar [value]="row.oi" [max]="maxOi()" [label]="oi(row.oi)" /></td>
          <td>
            <ay-data-bar
              [value]="row.oiChange"
              [max]="maxDelta()"
              [label]="signedOi(row.oiChange)"
              [tone]="oiTone(row.oiChange)"
            />
          </td>
          <td class="num" [class]="pctClass(row.oiPct)">{{ pct(row.oiPct) }}</td>
          <td><ay-oi-int-badge [value]="row.interpretation" /></td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="8">
            No bank-stock futures yet — the forward-only OI capture needs two snapshot buckets to
            accrue.
          </td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class OiBankGridPage {
  protected readonly store = inject(OiAnalyticsStore);
  private readonly ctx = inject(SymbolContextStore);

  // Sector-wide — react to mode/date/interval only (no symbol).
  private readonly reload = effect(() => {
    this.ctx.interval();
    this.ctx.mode();
    this.ctx.date();
    this.store.loadBankGrid();
  });

  protected maxOi(): number {
    return (this.store.bankGrid()?.items ?? []).reduce((m, r) => Math.max(m, r.oi ?? 0), 1);
  }

  protected maxDelta(): number {
    return (this.store.bankGrid()?.items ?? []).reduce(
      (m, r) => Math.max(m, Math.abs(r.oiChange)),
      1,
    );
  }

  protected dec(value: string | null | undefined, fractionDigits: number): string {
    return value ? formatDecimal(value, fractionDigits) : '—';
  }

  protected pct(value: string | null | undefined): string {
    if (!value) {
      return '—';
    }
    const formatted = formatDecimal(value, 2);
    return value.startsWith('-') ? formatted : '+' + formatted;
  }

  protected pctClass(value: string | null | undefined): string {
    if (!value || value === '0' || value === '0.00') {
      return '';
    }
    return value.startsWith('-') ? 'bear' : 'bull';
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

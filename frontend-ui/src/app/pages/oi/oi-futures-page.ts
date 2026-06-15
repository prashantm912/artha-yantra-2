import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { TableModule } from 'primeng/table';
import { formatDecimal } from '../../core/decimal';
import { OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { SymbolContextStore } from '../../stores/symbol-context.store';
import { DataBar } from '../../shared/data-bar';
import { OiControlBar } from './oi-control-bar';

/**
 * Futures OI Analysis (oipulse parity): per-contract OI / ΔOI / LTP with in-cell OI bars. Reads
 * GET /api/v1/market/futures/oi-analysis via {@link OiAnalyticsStore}, reloading on the shared
 * {@link SymbolContextStore} selection (expiry is ignored by the endpoint, so the control bar hides
 * it). LTP is a decimal string.
 */
@Component({
  selector: 'ay-oi-futures-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, DataBar, OiControlBar],
  styles: `
    .meta {
      margin: 0 0 0.7rem;
      color: var(--ay-text-muted);
      font-variant-numeric: tabular-nums;
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
      scrollHeight="62vh"
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
  });

  protected dec(value: string | null | undefined, fractionDigits: number): string {
    return value ? formatDecimal(value, fractionDigits) : '—';
  }

  protected oi(value: number | null | undefined): string {
    return value != null ? value.toLocaleString('en-IN') : '—';
  }

  /** Signed ΔOI label: '+' for increases (decreases already carry '-') — direction is not colour-only. */
  protected signedOi(value: number | null | undefined): string {
    if (value == null) {
      return '—';
    }
    return value > 0 ? '+' + value.toLocaleString('en-IN') : value.toLocaleString('en-IN');
  }

  /** ΔOI bar tone: 0 (and null) is neutral, so green never means "≥ 0". */
  protected oiTone(value: number | null | undefined): 'bull' | 'bear' | 'neutral' {
    if (value == null || value === 0) {
      return 'neutral';
    }
    return value > 0 ? 'bull' : 'bear';
  }
}

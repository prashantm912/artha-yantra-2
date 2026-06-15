import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { TableModule } from 'primeng/table';
import { formatDecimal } from '../../core/decimal';
import { OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { SymbolContextStore } from '../../stores/symbol-context.store';
import { DataBar } from '../../shared/data-bar';
import { OiIntBadge } from '../../shared/oi-int-badge';
import { OiControlBar } from './oi-control-bar';

/**
 * Futures OI Spurt (oipulse parity): the per-contract interval buildup grid behind GET
 * /api/v1/market/futures/spurt — each contract's interval ΔOI, spurt % and 4-state interpretation.
 * Futures endpoints ignore expiry; reloads on the shared {@link SymbolContextStore} selection.
 */
@Component({
  selector: 'ay-oi-futures-spurt-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, DataBar, OiIntBadge, OiControlBar],
  styles: `
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Futures OI spurt</h1>
    <ay-oi-control-bar />

    <p-table
      [value]="store.futSpurt()?.items ?? []"
      [scrollable]="true"
      scrollHeight="64vh"
      [loading]="store.loadingFutSpurt()"
      dataKey="tradingsymbol"
    >
      <ng-template #header>
        <tr>
          <th>Contract</th>
          <th class="num">LTP</th>
          <th class="num">OI</th>
          <th class="num">Δ OI</th>
          <th class="num">Spurt %</th>
          <th>Buildup</th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr>
          <td>{{ row.tradingsymbol }}</td>
          <td class="num">{{ dec(row.ltp, 2) }}</td>
          <td><ay-data-bar [value]="row.oi" [max]="maxOi()" [label]="oi(row.oi)" /></td>
          <td>
            <ay-data-bar
              [value]="row.oiChange"
              [max]="maxDelta()"
              [label]="signedOi(row.oiChange)"
              [tone]="oiTone(row.oiChange)"
            />
          </td>
          <td class="num">{{ dec(row.spurtPct, 2) }}</td>
          <td><ay-oi-int-badge [value]="row.interpretation" /></td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="6">
            No spurt yet — two snapshot buckets must accrue for an interval delta.
          </td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class OiFuturesSpurtPage {
  protected readonly store = inject(OiAnalyticsStore);
  private readonly ctx = inject(SymbolContextStore);

  private readonly reload = effect(() => {
    this.ctx.name();
    this.ctx.interval();
    this.ctx.mode();
    this.ctx.date();
    this.store.loadFutSpurt();
  });

  protected maxOi(): number {
    return (this.store.futSpurt()?.items ?? []).reduce((m, r) => Math.max(m, r.oi ?? 0), 1);
  }

  protected maxDelta(): number {
    return (this.store.futSpurt()?.items ?? []).reduce(
      (m, r) => Math.max(m, Math.abs(r.oiChange)),
      1,
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

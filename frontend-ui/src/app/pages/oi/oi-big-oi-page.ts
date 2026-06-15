import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { TableModule } from 'primeng/table';
import { formatDecimal } from '../../core/decimal';
import { OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { SymbolContextStore } from '../../stores/symbol-context.store';
import { DataBar } from '../../shared/data-bar';
import { OiControlBar } from './oi-control-bar';

/**
 * Options Big-OI (oipulse parity): the latest bucket's legs ranked by absolute interval ΔOI, with
 * the ATM-straddle premium and the latest OI-trend tag in the header. Reads /options/big-oi,
 * /premium and /trending via {@link OiAnalyticsStore}, reloading on the shared selection.
 */
@Component({
  selector: 'ay-oi-big-oi-page',
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
    <h1 class="ay-sr-only">Options Big OI</h1>
    <ay-oi-control-bar />

    <p class="meta" aria-live="polite">
      @if (store.premium(); as p) {
        @if (p.atmStraddle) {
          ATM straddle {{ dec(p.atmStraddle, 2) }} @ {{ dec(p.atmStrike, 0) }} ·
        }
      }
      OI trend {{ trendLabel() }}
    </p>

    <p-table
      [value]="store.bigOi()?.items ?? []"
      [scrollable]="true"
      scrollHeight="62vh"
      [loading]="store.loadingBigOi()"
      dataKey="strike"
    >
      <ng-template #header>
        <tr>
          <th>Strike</th>
          <th>Side</th>
          <th class="num">LTP</th>
          <th class="num">OI</th>
          <th class="num">Δ OI</th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr>
          <td class="num">{{ dec(row.strike, 0) }}</td>
          <td>{{ row.optionType }}</td>
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
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="5">No OI movers for this selection.</td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class OiBigOiPage {
  protected readonly store = inject(OiAnalyticsStore);
  private readonly ctx = inject(SymbolContextStore);

  private readonly reload = effect(() => {
    this.ctx.name();
    this.ctx.expiry();
    this.ctx.interval();
    this.ctx.mode();
    this.ctx.date();
    this.store.loadBigOi();
    this.store.loadPremium();
    this.store.loadTrending();
  });

  protected trendLabel(): string {
    const items = this.store.trend()?.items ?? [];
    return items.length ? items[items.length - 1].trend : '—';
  }

  protected maxOi(): number {
    return (this.store.bigOi()?.items ?? []).reduce((m, r) => Math.max(m, r.oi), 1);
  }

  protected maxDelta(): number {
    return (this.store.bigOi()?.items ?? []).reduce((m, r) => Math.max(m, Math.abs(r.oiChange)), 1);
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

import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { TableModule } from 'primeng/table';
import { formatDecimal } from '../../core/decimal';
import { OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { SymbolContextStore } from '../../stores/symbol-context.store';
import { DataBar } from '../../shared/data-bar';
import { OiIntBadge } from '../../shared/oi-int-badge';
import { OiControlBar } from './oi-control-bar';

/**
 * Options OI Spurt (oipulse parity): the per-strike interval buildup grid behind GET
 * /api/v1/market/options/spurt — each leg's interval ΔOI, spurt % and 4-state interpretation, plus
 * the underlying OI-bias rollup. Reloads on the shared {@link SymbolContextStore} selection.
 */
@Component({
  selector: 'ay-oi-spurt-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, DataBar, OiIntBadge, OiControlBar],
  styles: `
    .bias {
      margin: 0 0 0.7rem;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      color: var(--ay-text-muted);
    }
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Options OI spurt</h1>
    <ay-oi-control-bar />

    <p class="bias" aria-live="polite">
      OI bias <ay-oi-int-badge [value]="store.oiInterpretation()" />
    </p>

    <p-table
      [value]="store.spurt()?.items ?? []"
      [scrollable]="true"
      scrollHeight="62vh"
      [loading]="store.loadingSpurt()"
      dataKey="strike"
    >
      <ng-template #header>
        <tr>
          <th>Strike</th>
          <th>Side</th>
          <th class="num">LTP</th>
          <th class="num">OI</th>
          <th class="num">Δ OI</th>
          <th class="num">Spurt %</th>
          <th>Buildup</th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr>
          <td class="num">{{ dec(row.strike, 0) }}</td>
          <td>{{ row.optionType }}</td>
          <td class="num">{{ dec(row.ltp, 2) }}</td>
          <td>
            <ay-data-bar [value]="row.oi ?? 0" [max]="maxOi()" [label]="oi(row.oi)" />
          </td>
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
          <td colspan="7">
            No spurt yet — two snapshot buckets must accrue for an interval delta.
          </td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class OiSpurtPage {
  protected readonly store = inject(OiAnalyticsStore);
  private readonly ctx = inject(SymbolContextStore);

  private readonly reload = effect(() => {
    this.ctx.name();
    this.ctx.expiry();
    this.ctx.interval();
    this.ctx.mode();
    this.ctx.date();
    this.store.loadSpurt();
  });

  protected maxOi(): number {
    return (this.store.spurt()?.items ?? []).reduce((m, r) => Math.max(m, r.oi ?? 0), 1);
  }

  protected maxDelta(): number {
    return (this.store.spurt()?.items ?? []).reduce((m, r) => Math.max(m, Math.abs(r.oiChange)), 1);
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

import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { TableModule } from 'primeng/table';
import { compareDecimal, formatDecimal } from '../../core/decimal';
import { OiAnalyticsStore, type OiChainRow } from '../../stores/oi-analytics.store';
import { SymbolContextStore } from '../../stores/symbol-context.store';
import { DataBar } from '../../shared/data-bar';
import { OiIntBadge } from '../../shared/oi-int-badge';
import { OiControlBar } from './oi-control-bar';

/**
 * Options OI Analysis (oipulse parity): the mirrored CE/PE strike grid with in-cell OI bars, plus a
 * PCR / max-pain / active-strike-sentiment header. Reads the three options endpoints
 * (oi-stats · active-strikes · oi-analysis) via {@link OiAnalyticsStore}, reloading whenever the
 * shared {@link SymbolContextStore} selection changes. All money/IV values are decimal strings.
 */
@Component({
  selector: 'ay-oi-options-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, DataBar, OiIntBadge, OiControlBar],
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
    .strike {
      text-align: center;
      font-weight: 600;
    }
    .up {
      color: var(--ay-bull);
    }
    .down {
      color: var(--ay-bear);
    }
    .itm {
      background: color-mix(in srgb, var(--ay-accent) 12%, transparent);
    }
    .bias {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      margin: 0 0 0.6rem;
      color: var(--ay-text-muted);
    }
  `,
  template: `
    <h1 class="ay-sr-only">Options OI analysis</h1>
    <ay-oi-control-bar />

    <p class="bias" aria-live="polite">
      OI bias <ay-oi-int-badge [value]="store.oiInterpretation()" />
    </p>

    @if (store.oiStats(); as s) {
      <p class="meta" aria-live="polite">
        PCR {{ dec(s.pcr, 4) }} · Max pain {{ dec(s.maxPain, 2) }} · CE OI {{ oi(s.ceOi) }} · PE OI
        {{ oi(s.peOi) }}
        @if (store.activeStrikes(); as a) {
          · Sentiment {{ dec(a.sentimentPct, 2) }}%
        }
        · {{ store.chainRows().length }} strike{{ store.chainRows().length === 1 ? '' : 's' }}
      </p>
    } @else {
      <p class="meta">No OI stats — pick an underlying + expiry with captured snapshots.</p>
    }

    <p-table
      [value]="store.chainRows()"
      [scrollable]="true"
      scrollHeight="62vh"
      [loading]="store.loadingOptions()"
      dataKey="strike"
    >
      <ng-template #header>
        <tr>
          <th class="num">CE OI</th>
          <th class="num">CE ΔOI</th>
          <th class="num">CE IV</th>
          <th class="num">CE LTP</th>
          <th class="strike">Strike</th>
          <th class="num">PE LTP</th>
          <th class="num">PE IV</th>
          <th class="num">PE ΔOI</th>
          <th class="num">PE OI</th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr>
          <td>
            <ay-data-bar
              [value]="row.ce?.oi ?? 0"
              [max]="store.maxOptionOi()"
              [label]="oi(row.ce?.oi)"
            />
          </td>
          <td class="num" [class]="toneClass(row.ce?.oiChange)">
            {{ signedOi(row.ce?.oiChange) }}
          </td>
          <td class="num">{{ dec(row.ce?.iv, 4) }}</td>
          <td class="num" [class.itm]="ceItm(row)">
            {{ dec(row.ce?.ltp, 2) }}
            @if (ceItm(row)) {
              <span class="ay-sr-only"> in the money</span>
            }
          </td>
          <td class="strike">{{ row.strike }}</td>
          <td class="num" [class.itm]="peItm(row)">
            {{ dec(row.pe?.ltp, 2) }}
            @if (peItm(row)) {
              <span class="ay-sr-only"> in the money</span>
            }
          </td>
          <td class="num">{{ dec(row.pe?.iv, 4) }}</td>
          <td class="num" [class]="toneClass(row.pe?.oiChange)">
            {{ signedOi(row.pe?.oiChange) }}
          </td>
          <td>
            <ay-data-bar
              [value]="row.pe?.oi ?? 0"
              [max]="store.maxOptionOi()"
              [label]="oi(row.pe?.oi)"
            />
          </td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="9">No chain snapshots for this selection.</td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class OiOptionsPage {
  protected readonly store = inject(OiAnalyticsStore);
  private readonly ctx = inject(SymbolContextStore);

  // Reload whenever any part of the shared selection changes.
  private readonly reload = effect(() => {
    this.ctx.name();
    this.ctx.expiry();
    this.ctx.interval();
    this.ctx.mode();
    this.ctx.date();
    this.store.loadOptions();
    this.store.loadSpurt();
  });

  protected dec(value: string | null | undefined, fractionDigits: number): string {
    return value ? formatDecimal(value, fractionDigits) : '—';
  }

  protected oi(value: number | null | undefined): string {
    return value != null ? value.toLocaleString('en-IN') : '—';
  }

  protected toneClass(oiChange: number | null | undefined): string {
    if (oiChange == null || oiChange === 0) {
      return '';
    }
    return oiChange > 0 ? 'up' : 'down';
  }

  /** Signed ΔOI label: '+' for increases (decreases already carry '-') — direction is not colour-only. */
  protected signedOi(value: number | null | undefined): string {
    if (value == null) {
      return '—';
    }
    return value > 0 ? '+' + value.toLocaleString('en-IN') : value.toLocaleString('en-IN');
  }

  protected ceItm(row: OiChainRow): boolean {
    const spot = row.spot ?? this.store.spot();
    return spot != null && compareDecimal(row.strike, spot) < 0;
  }

  protected peItm(row: OiChainRow): boolean {
    const spot = row.spot ?? this.store.spot();
    return spot != null && compareDecimal(row.strike, spot) > 0;
  }
}

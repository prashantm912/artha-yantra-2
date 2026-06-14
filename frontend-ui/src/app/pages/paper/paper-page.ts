import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { formatDecimal, isNegative, multiplyByInt, subtractDecimal } from '../../core/decimal';
import { EquityCurveComponent, type CurvePoint } from '../../shared/equity-curve';
import { MarketStore } from '../../stores/market.store';
import { PaperStore } from '../../stores/paper.store';

/**
 * /paper (F-43): the paper-trading ledger — open positions with LIVE mark-to-market (recomputed
 * from the per-symbol tick map, §F.2), the closed-trade ledger and the realized-equity curve
 * (lightweight-charts). Unrealized P&L is computed here, never stored.
 */
@Component({
  selector: 'ay-paper-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, TagModule, ButtonModule, EquityCurveComponent],
  styles: `
    .summary {
      display: flex;
      gap: 1.2rem;
      align-items: center;
      flex-wrap: wrap;
      margin-bottom: 0.8rem;
    }
    .summary .metric {
      font-variant-numeric: tabular-nums;
    }
    .summary .metric .v {
      font-size: 1.2rem;
      font-weight: 700;
    }
    .summary .k {
      color: var(--ay-text-muted);
      font-size: 0.8rem;
    }
    .spacer {
      flex: 1;
    }
    .grid {
      display: grid;
      grid-template-columns: 1.5fr 1fr;
      gap: 1rem;
      align-items: start;
    }
    .num {
      font-variant-numeric: tabular-nums;
      text-align: right;
    }
    .pos {
      color: var(--ay-success, #16a34a);
    }
    .neg {
      color: var(--ay-bear, #ef4444);
    }
    .curve {
      height: 16rem;
    }
    h2 {
      font-size: 1rem;
      margin: 0 0 0.5rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Paper trading</h1>
    <div class="summary">
      @if (store.pnl(); as pnl) {
        <div class="metric">
          <div class="k">Realized P&L</div>
          <div
            class="v"
            [class.pos]="!neg(pnl.summary.realizedTotal)"
            [class.neg]="neg(pnl.summary.realizedTotal)"
          >
            {{ money(pnl.summary.realizedTotal) }}
          </div>
        </div>
        <div class="metric">
          <div class="k">Trades</div>
          <div class="v">{{ pnl.summary.trades }}</div>
        </div>
        <div class="metric">
          <div class="k">Win rate</div>
          <div class="v">{{ pnl.summary.winRate ? pct(pnl.summary.winRate) : '—' }}</div>
        </div>
        <div class="metric">
          <div class="k">Expectancy</div>
          <div class="v">{{ pnl.summary.expectancy ? money(pnl.summary.expectancy) : '—' }}</div>
        </div>
      }
      <span class="spacer"></span>
      <p-button
        size="small"
        severity="danger"
        [outlined]="true"
        label="Reset ledger"
        icon="pi pi-trash"
        (onClick)="store.reset(true)"
      />
    </div>

    <div class="grid">
      <section>
        <h2>Open positions</h2>
        <p-table [value]="livePositions()" dataKey="id" [loading]="store.loading()">
          <ng-template #header>
            <tr>
              <th>Instrument</th>
              <th>Side</th>
              <th class="num">Qty</th>
              <th class="num">Avg</th>
              <th class="num">Mark</th>
              <th class="num">Unrealized</th>
              <th></th>
            </tr>
          </ng-template>
          <ng-template #body let-p>
            <tr>
              <td>{{ p.exchange }}:{{ p.tradingsymbol }}</td>
              <td><p-tag [severity]="p.side === 'BUY' ? 'success' : 'warn'" [value]="p.side" /></td>
              <td class="num">{{ p.qty }}</td>
              <td class="num">{{ money(p.avgEntryPrice) }}</td>
              <td class="num">{{ p.mark ? money(p.mark) : '—' }}</td>
              <td
                class="num"
                [class.pos]="p.unrealized && !neg(p.unrealized)"
                [class.neg]="p.unrealized && neg(p.unrealized)"
              >
                {{ p.unrealized ? money(p.unrealized) : '—' }}
              </td>
              <td>
                <p-button size="small" [text]="true" label="Close" (onClick)="store.close(p.id)" />
              </td>
            </tr>
          </ng-template>
          <ng-template #emptymessage>
            <tr>
              <td colspan="7">No open positions — take a signal or place a paper order.</td>
            </tr>
          </ng-template>
        </p-table>

        <h2 style="margin-top:1rem">Closed trades</h2>
        <p-table [value]="store.trades()" dataKey="id" scrollHeight="20rem" [scrollable]="true">
          <ng-template #header>
            <tr>
              <th>Instrument</th>
              <th>Side</th>
              <th class="num">Qty</th>
              <th class="num">Avg</th>
              <th class="num">Realized</th>
              <th>Closed</th>
            </tr>
          </ng-template>
          <ng-template #body let-t>
            <tr>
              <td>{{ t.exchange }}:{{ t.tradingsymbol }}</td>
              <td>{{ t.side }}</td>
              <td class="num">{{ t.qty }}</td>
              <td class="num">{{ money(t.avgEntryPrice) }}</td>
              <td class="num" [class.pos]="!neg(t.realizedPnl)" [class.neg]="neg(t.realizedPnl)">
                {{ money(t.realizedPnl) }}
              </td>
              <td>{{ t.closedAt?.slice(0, 19) }}</td>
            </tr>
          </ng-template>
          <ng-template #emptymessage>
            <tr>
              <td colspan="6">No closed trades yet.</td>
            </tr>
          </ng-template>
        </p-table>
      </section>

      <section>
        <h2>Realized equity</h2>
        <div class="curve"><ay-equity-curve [equity]="curve()" /></div>
      </section>
    </div>
  `,
})
export class PaperPage {
  protected readonly store = inject(PaperStore);
  private readonly market = inject(MarketStore);
  private tracked = new Set<string>();

  /** Positions with live unrealized P&L recomputed from the per-symbol tick map. */
  protected readonly livePositions = computed(() => {
    const ticks = this.market.ticks();
    return this.store.positions().map((p) => {
      const tick = ticks[`${p.exchange}:${p.tradingsymbol}`];
      const mark = tick?.lastPrice ?? p.markPrice;
      let unrealized = p.unrealizedPnl;
      if (mark) {
        const perUnit =
          p.side === 'BUY'
            ? subtractDecimal(mark, p.avgEntryPrice)
            : subtractDecimal(p.avgEntryPrice, mark);
        unrealized = multiplyByInt(perUnit, p.qty);
      }
      return { ...p, mark, unrealized };
    });
  });

  protected readonly curve = computed<CurvePoint[]>(() =>
    (this.store.pnl()?.points ?? []).map((point) => ({ ts: point.date, value: point.equity })),
  );

  constructor() {
    // keep a live tick subscription per open-position symbol (refcounted; not the firehose)
    effect(() => {
      const keys = this.store.positions().map((p) => `${p.exchange}:${p.tradingsymbol}`);
      const next = new Set(keys);
      const added = keys.filter((k) => !this.tracked.has(k));
      const removed = [...this.tracked].filter((k) => !next.has(k));
      if (added.length) {
        this.market.track(added);
      }
      if (removed.length) {
        this.market.untrack(removed);
      }
      this.tracked = next;
    });
    inject(DestroyRef).onDestroy(() => {
      if (this.tracked.size) {
        this.market.untrack([...this.tracked]);
      }
    });
  }

  protected money(value: string): string {
    return formatDecimal(value, 2);
  }

  protected pct(value: string): string {
    return `${formatDecimal(multiplyByInt(value, 100), 1)}%`;
  }

  protected neg(value: string): boolean {
    return isNegative(value);
  }
}

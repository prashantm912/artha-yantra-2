import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
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
  imports: [
    FormsModule,
    TableModule,
    TagModule,
    ButtonModule,
    InputTextModule,
    EquityCurveComponent,
  ],
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
    .account {
      display: flex;
      gap: 1.2rem;
      align-items: center;
      flex-wrap: wrap;
      margin-bottom: 0.6rem;
      padding-bottom: 0.6rem;
      border-bottom: 1px solid var(--ay-border);
    }
    .account .metric {
      font-variant-numeric: tabular-nums;
    }
    .account .metric .v {
      font-size: 1.2rem;
      font-weight: 700;
    }
    .account .k {
      color: var(--ay-text-muted);
      font-size: 0.8rem;
    }
    .cap-edit {
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }
    .cap-edit input {
      width: 9rem;
    }
    .risk {
      display: flex;
      gap: 1.5rem;
      align-items: center;
      flex-wrap: wrap;
      margin-bottom: 0.8rem;
      font-size: 0.85rem;
      color: var(--ay-text-muted);
    }
    .risk .limit input {
      width: 7rem;
      margin: 0 0.3rem;
    }
    .risk .limit.on {
      color: var(--ay-text);
      font-weight: 600;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Paper trading</h1>

    @if (store.account(); as acct) {
      <section class="account">
        <div class="metric">
          <div class="k">Equity</div>
          <div class="v">{{ money(acct.equity) }}</div>
        </div>
        <div class="metric">
          <div class="k">Free cash</div>
          <div class="v">{{ money(acct.cash) }}</div>
        </div>
        <div class="metric">
          <div class="k">Day P&L</div>
          <div class="v" [class.pos]="!neg(acct.dayPnl)" [class.neg]="neg(acct.dayPnl)">
            {{ money(acct.dayPnl) }}
          </div>
        </div>
        <div class="metric">
          <div class="k">Capital used</div>
          <div class="v">{{ money(acct.capitalUsed) }}</div>
        </div>
        <div class="cap-edit">
          <span class="k">Starting capital</span>
          <input
            pInputText
            [ngModel]="capitalDraft()"
            (ngModelChange)="capitalDraft.set($event)"
            [placeholder]="acct.startingCapital"
          />
          <p-button size="small" [text]="true" label="Set" (onClick)="saveCapital()" />
        </div>
        <span class="spacer"></span>
        <p-button
          size="small"
          [severity]="killOn() ? 'danger' : 'secondary'"
          [label]="killOn() ? 'KILL SWITCH ON' : 'Kill switch'"
          icon="pi pi-power-off"
          (onClick)="toggleKill()"
        />
      </section>
      <section class="risk">
        <span class="limit" [class.on]="enabled('max_open_paper_positions')">
          Max open
          <input pInputText [ngModel]="maxOpenDraft()" (ngModelChange)="maxOpenDraft.set($event)" />
          <p-button size="small" [text]="true" label="Apply" (onClick)="applyMaxOpen()" />
          <p-button
            size="small"
            [text]="true"
            severity="secondary"
            label="Off"
            (onClick)="disable('max_open_paper_positions')"
          />
        </span>
        <span class="limit" [class.on]="enabled('daily_loss_limit')">
          Daily loss ₹
          <input
            pInputText
            [ngModel]="dailyLossDraft()"
            (ngModelChange)="dailyLossDraft.set($event)"
          />
          <p-button size="small" [text]="true" label="Apply" (onClick)="applyDailyLoss()" />
          <p-button
            size="small"
            [text]="true"
            severity="secondary"
            label="Off"
            (onClick)="disable('daily_loss_limit')"
          />
        </span>
      </section>
    }

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

  protected readonly capitalDraft = signal('');
  protected readonly maxOpenDraft = signal('');
  protected readonly dailyLossDraft = signal('');

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

  protected enabled(key: string): boolean {
    return this.store.risk().find((r) => r.key === key)?.value?.['enabled'] === true;
  }

  protected killOn(): boolean {
    return this.enabled('kill_switch');
  }

  protected toggleKill(): void {
    this.store.updateRisk('kill_switch', { enabled: !this.killOn() });
  }

  protected saveCapital(): void {
    if (this.capitalDraft().trim()) {
      this.store.updateStartingCapital(this.capitalDraft().trim());
    }
  }

  protected applyMaxOpen(): void {
    this.store.updateRisk('max_open_paper_positions', {
      enabled: true,
      value: Number(this.maxOpenDraft()),
    });
  }

  protected applyDailyLoss(): void {
    this.store.updateRisk('daily_loss_limit', {
      enabled: true,
      mode: 'inr',
      value: Number(this.dailyLossDraft()),
    });
  }

  protected disable(key: string): void {
    this.store.updateRisk(key, { enabled: false });
  }
}

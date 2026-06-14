import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatDecimal, isNegative } from '../../../core/decimal';
import { PaperStore } from '../../../stores/paper.store';

/** Dashboard at-a-glance paper P&L tile (§F.8): realized total, trade count, open positions. */
@Component({
  selector: 'ay-paper-pnl-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  styles: `
    .row {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      padding: 0.2rem 0;
      font-variant-numeric: tabular-nums;
    }
    .k {
      color: var(--ay-text-muted);
      font-size: 0.82rem;
    }
    .big {
      font-size: 1.5rem;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }
    .pos {
      color: var(--ay-success, #16a34a);
    }
    .neg {
      color: var(--ay-bear, #ef4444);
    }
    a {
      color: var(--ay-accent, #38bdf8);
      font-size: 0.82rem;
      text-decoration: none;
    }
  `,
  template: `
    @if (store.pnl(); as pnl) {
      <div
        class="big"
        [class.pos]="!neg(pnl.summary.realizedTotal)"
        [class.neg]="neg(pnl.summary.realizedTotal)"
      >
        {{ money(pnl.summary.realizedTotal) }}
      </div>
      <div class="row">
        <span class="k">Closed trades</span><span>{{ pnl.summary.trades }}</span>
      </div>
      <div class="row">
        <span class="k">Open positions</span><span>{{ store.positions().length }}</span>
      </div>
      <div class="row">
        <span class="k">Win rate</span
        ><span>{{ pnl.summary.winRate ? rate(pnl.summary.winRate) : '—' }}</span>
      </div>
      <a routerLink="/paper">Open paper ledger →</a>
    } @else {
      <div class="k">No paper activity yet.</div>
    }
  `,
})
export class PaperPnlWidget {
  protected readonly store = inject(PaperStore);

  protected money(value: string): string {
    return formatDecimal(value, 2);
  }

  protected rate(value: string): string {
    return `${formatDecimal(value, 4)}`;
  }

  protected neg(value: string): boolean {
    return isNegative(value);
  }
}

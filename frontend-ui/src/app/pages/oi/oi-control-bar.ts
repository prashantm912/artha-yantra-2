import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { MarketStore } from '../../stores/market.store';
import {
  OI_INTERVALS,
  type OiInterval,
  SymbolContextStore,
} from '../../stores/symbol-context.store';

/**
 * The universal OI control bar (oipulse Mode·Name·Date·Expiry·Interval): binds to the shared
 * {@link SymbolContextStore} so a selection carries across every analytics page. Expiry hidden for
 * futures (`[showExpiry]="false"`) — the futures endpoint ignores it. Mutating the context is what
 * a page reacts to (via an effect) to reload; the bar itself fires no requests.
 */
@Component({
  selector: 'ay-oi-control-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, SelectModule, ButtonModule],
  styles: `
    .controls {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.6rem;
      margin-bottom: 0.8rem;
    }
    .date {
      height: 2.5rem;
      padding: 0 0.6rem;
      border: 1px solid var(--ay-border);
      border-radius: 6px;
      background: var(--ay-surface-1);
      color: var(--ay-text);
    }
  `,
  template: `
    <div class="controls">
      @if (showName()) {
        <p-select
          [options]="underlyings()"
          [ngModel]="ctx.name()"
          (ngModelChange)="ctx.setName($event)"
          [filter]="true"
          ariaLabel="Underlying"
          placeholder="Underlying"
        />
      }
      @if (showExpiry()) {
        <p-select
          [options]="ctx.expiries()"
          [ngModel]="ctx.expiry()"
          (ngModelChange)="ctx.setExpiry($event)"
          ariaLabel="Expiry"
          placeholder="Expiry"
        />
      }
      <p-select
        [options]="intervals"
        [ngModel]="ctx.interval()"
        (ngModelChange)="ctx.setInterval($event)"
        ariaLabel="Interval"
        placeholder="Interval"
      />
      <p-button
        [outlined]="true"
        ariaLabel="Toggle live/history mode"
        [label]="ctx.mode() === 'history' ? 'History' : 'Live'"
        [attr.aria-pressed]="ctx.mode() === 'history'"
        (onClick)="ctx.setMode(ctx.mode() === 'history' ? 'live' : 'history')"
      />
      @if (ctx.mode() === 'history') {
        <input
          class="date"
          type="date"
          aria-label="History date"
          [ngModel]="ctx.date()"
          (ngModelChange)="ctx.setDate($event)"
        />
      }
    </div>
  `,
})
export class OiControlBar {
  protected readonly ctx = inject(SymbolContextStore);
  private readonly market = inject(MarketStore);

  readonly showExpiry = input(true);
  readonly showName = input(true);
  protected readonly intervals: OiInterval[] = [...OI_INTERVALS];

  // Fall back to the two index defaults until the instrument-master underlyings load.
  protected readonly underlyings = computed(() => {
    const loaded = this.market.underlyings();
    return loaded.length > 0 ? loaded : ['NIFTY 50', 'NIFTY BANK'];
  });

  constructor() {
    this.market.loadUnderlyings();
  }
}

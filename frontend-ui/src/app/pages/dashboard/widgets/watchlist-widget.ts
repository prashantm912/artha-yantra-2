import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { TableModule } from 'primeng/table';
import { formatDecimal } from '../../../core/decimal';
import { MarketStore, canonicalKey } from '../../../stores/market.store';
import { PulseDirective } from '../../../shared/pulse.directive';

interface WatchItem {
  exchange: string;
  tradingsymbol: string;
}
interface WatchlistView {
  id: string;
  name: string;
  items: WatchItem[];
}

/**
 * Watchlist widget (E-2/E-3): the first named watchlist's instruments as a scrollable live tick
 * table, prices CSS-pulsing on each update (not row re-creation).
 */
@Component({
  selector: 'ay-watchlist-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, PulseDirective],
  styles: `
    .price {
      font-variant-numeric: tabular-nums;
    }
    .empty {
      color: var(--ay-text-muted);
      font-size: 0.85rem;
    }
  `,
  template: `
    @if (rows().length) {
      <p-table [value]="rows()" [scrollable]="true" scrollHeight="16rem" dataKey="key">
        <ng-template #header>
          <tr>
            <th>Instrument</th>
            <th>Last</th>
            <th>Volume</th>
          </tr>
        </ng-template>
        <ng-template #body let-row>
          <tr style="height: 34px">
            <td>{{ row.tradingsymbol }}</td>
            <td class="price" [ayPulse]="row.last">{{ row.last }}</td>
            <td class="price">{{ row.volume }}</td>
          </tr>
        </ng-template>
      </p-table>
    } @else {
      <p class="empty">No watchlist instruments — add some on the Watchlists page.</p>
    }
  `,
})
export class WatchlistWidget {
  private readonly http = inject(HttpClient);
  private readonly market = inject(MarketStore);
  private readonly items = signal<WatchItem[]>([]);

  protected readonly rows = computed(() => {
    const ticks = this.market.ticks();
    return this.items().map((it) => {
      const key = canonicalKey(it.exchange, it.tradingsymbol);
      const t = ticks[key];
      return {
        key,
        tradingsymbol: `${it.exchange}:${it.tradingsymbol}`,
        last: t ? formatDecimal(t.lastPrice, 2) : '—',
        volume: t ? t.cumulativeDayVolume : '—',
      };
    });
  });

  private tracked: string[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.market.untrack(this.tracked));
    this.http.get<{ items: WatchlistView[] }>('/api/v1/watchlists').subscribe({
      next: (resp) => {
        // Pick the first watchlist that HAS instruments, not just the alphabetically-first one — a
        // newly-created populated list otherwise shows empty behind an empty default (D16).
        const first = resp.items?.find((w) => w.items.length > 0) ?? resp.items?.[0];
        const its = first?.items ?? [];
        this.items.set(its);
        this.tracked = its.map((it) => canonicalKey(it.exchange, it.tradingsymbol));
        this.market.track(this.tracked);
      },
      error: () => undefined,
    });
  }
}

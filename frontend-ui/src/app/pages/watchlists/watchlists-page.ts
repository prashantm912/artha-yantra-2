import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { AutoCompleteModule, type AutoCompleteCompleteEvent } from 'primeng/autocomplete';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TabsModule } from 'primeng/tabs';
import { formatDecimal } from '../../core/decimal';
import { MarketStore, canonicalKey, type Instrument } from '../../stores/market.store';
import { PulseDirective } from '../../shared/pulse.directive';

interface WatchItem {
  exchange: string;
  tradingsymbol: string;
}
interface WatchlistView {
  id: string;
  name: string;
  items: WatchItem[];
}
interface ScreenerRow {
  exchange: string;
  tradingsymbol: string;
  latestClose: string;
  value: string;
  avgVolume?: string | null;
  label?: string | null;
}

/**
 * /watchlists (Phase 35, E-8): named watchlists with live tick rows (add/remove instruments) plus
 * the screener tab (the Phase-17 endpoint's UI consumer). Live prices come from the MarketStore
 * tick map (refcounted per-symbol subscriptions), pulsing on update.
 */
@Component({
  selector: 'ay-watchlists-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TabsModule,
    TableModule,
    ButtonModule,
    SelectModule,
    InputTextModule,
    AutoCompleteModule,
    PulseDirective,
  ],
  styles: `
    .row {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      margin-bottom: 0.8rem;
      flex-wrap: wrap;
    }
    .price {
      font-variant-numeric: tabular-nums;
    }
    .muted {
      color: var(--ay-text-muted);
      font-size: 0.85rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Watchlists & screener</h1>
    <p-tabs value="lists">
      <p-tablist>
        <p-tab value="lists">Watchlists</p-tab>
        <p-tab value="screener">Screener</p-tab>
      </p-tablist>
      <p-tabpanels>
        <p-tabpanel value="lists">
          <div class="row">
            <p-select
              [options]="lists()"
              optionLabel="name"
              [ngModel]="selected()"
              (ngModelChange)="selectList($event)"
              placeholder="Watchlist"
              styleClass="w-14rem"
            />
            <input pInputText placeholder="New watchlist name" [(ngModel)]="newName" />
            <p-button label="Create" icon="pi pi-plus" size="small" (onClick)="create()" />
          </div>

          @if (selected(); as list) {
            <div class="row">
              <p-autocomplete
                [suggestions]="market.searchResults()"
                (completeMethod)="search($event)"
                field="tradingsymbol"
                placeholder="Add instrument…"
                [(ngModel)]="picked"
                (onSelect)="addPicked(list)"
              />
            </div>
            <p-table
              [value]="rows()"
              dataKey="key"
              [scrollable]="true"
              scrollHeight="calc(100vh - 18rem)"
            >
              <ng-template #header>
                <tr>
                  <th>Instrument</th>
                  <th>Last</th>
                  <th>Volume</th>
                  <th></th>
                </tr>
              </ng-template>
              <ng-template #body let-row>
                <tr>
                  <td>{{ row.label }}</td>
                  <td class="price" [ayPulse]="row.last">{{ row.last }}</td>
                  <td class="price">{{ row.volume }}</td>
                  <td>
                    <p-button
                      size="small"
                      [text]="true"
                      severity="danger"
                      icon="pi pi-trash"
                      [ariaLabel]="'Remove ' + row.label"
                      (onClick)="remove(list, row)"
                    />
                  </td>
                </tr>
              </ng-template>
              <ng-template #emptymessage>
                <tr>
                  <td colspan="4" class="muted">Empty — add an instrument above.</td>
                </tr>
              </ng-template>
            </p-table>
          } @else {
            <p class="muted">No watchlist selected. Create one to start tracking instruments.</p>
          }
        </p-tabpanel>

        <p-tabpanel value="screener">
          <div class="row">
            <p-select
              [options]="presets"
              [ngModel]="preset()"
              (ngModelChange)="preset.set($event)"
              placeholder="Preset"
            />
            <p-button label="Run" icon="pi pi-search" size="small" (onClick)="runScreener()" />
          </div>
          <p-table [value]="screener()" [scrollable]="true" scrollHeight="calc(100vh - 16rem)">
            <ng-template #header>
              <tr>
                <th>Instrument</th>
                <th>Close</th>
                <th>Value</th>
                <th>Label</th>
              </tr>
            </ng-template>
            <ng-template #body let-r>
              <tr>
                <td>{{ r.exchange }}:{{ r.tradingsymbol }}</td>
                <td class="price">{{ price(r.latestClose) }}</td>
                <td class="price">{{ r.value }}</td>
                <td>{{ r.label ?? '—' }}</td>
              </tr>
            </ng-template>
            <ng-template #emptymessage>
              <tr>
                <td colspan="4" class="muted">Pick a preset and run the screener.</td>
              </tr>
            </ng-template>
          </p-table>
        </p-tabpanel>
      </p-tabpanels>
    </p-tabs>
  `,
})
export class WatchlistsPage {
  protected readonly market = inject(MarketStore);
  private readonly http = inject(HttpClient);

  protected readonly lists = signal<WatchlistView[]>([]);
  protected readonly selected = signal<WatchlistView | null>(null);
  protected readonly screener = signal<ScreenerRow[]>([]);
  protected readonly preset = signal<string>('momentum');
  protected readonly presets = ['momentum', 'long_term', 'oi_buildup', 'rs_rank'];
  protected newName = '';
  protected picked: Instrument | string = '';

  private tracked: string[] = [];

  protected readonly rows = computed(() => {
    const ticks = this.market.ticks();
    return (this.selected()?.items ?? []).map((it) => {
      const key = canonicalKey(it.exchange, it.tradingsymbol);
      const t = ticks[key];
      return {
        key,
        exchange: it.exchange,
        tradingsymbol: it.tradingsymbol,
        label: `${it.exchange}:${it.tradingsymbol}`,
        last: t ? formatDecimal(t.lastPrice, 2) : '—',
        volume: t ? t.cumulativeDayVolume : '—',
      };
    });
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.market.untrack(this.tracked));
    this.reload();
  }

  private reload(keepId?: string): void {
    this.http.get<WatchlistView[]>('/api/v1/watchlists').subscribe({
      next: (lists) => {
        this.lists.set(lists ?? []);
        const target = (lists ?? []).find((l) => l.id === keepId) ?? lists?.[0] ?? null;
        this.selectList(target);
      },
      error: () => undefined,
    });
  }

  protected selectList(list: WatchlistView | null): void {
    this.selected.set(list);
    this.market.untrack(this.tracked);
    this.tracked = (list?.items ?? []).map((it) => canonicalKey(it.exchange, it.tradingsymbol));
    this.market.track(this.tracked);
  }

  protected create(): void {
    const name = this.newName.trim();
    if (!name) {
      return;
    }
    this.http.post<WatchlistView>('/api/v1/watchlists', { name }).subscribe({
      next: (created) => {
        this.newName = '';
        this.reload(created.id);
      },
      error: () => undefined,
    });
  }

  protected search(event: AutoCompleteCompleteEvent): void {
    this.market.search(event.query);
  }

  protected addPicked(list: WatchlistView): void {
    const inst = this.picked;
    if (!inst || typeof inst === 'string') {
      return;
    }
    this.http
      .post(`/api/v1/watchlists/${list.id}/items`, {
        exchange: inst.exchange,
        tradingsymbol: inst.tradingsymbol,
      })
      .subscribe({
        next: () => {
          this.picked = '';
          this.reload(list.id);
        },
        error: () => undefined,
      });
  }

  protected remove(list: WatchlistView, row: { exchange: string; tradingsymbol: string }): void {
    this.http
      .delete(`/api/v1/watchlists/${list.id}/items`, {
        body: { exchange: row.exchange, tradingsymbol: row.tradingsymbol },
      })
      .subscribe({ next: () => this.reload(list.id), error: () => undefined });
  }

  protected runScreener(): void {
    const params = new HttpParams().set('preset', this.preset()).set('limit', 50);
    this.http.get<{ items: ScreenerRow[] }>('/api/v1/market/screener', { params }).subscribe({
      next: (resp) => this.screener.set(resp.items ?? []),
      error: () => this.screener.set([]),
    });
  }

  protected price(value: string): string {
    return formatDecimal(value, 2);
  }
}

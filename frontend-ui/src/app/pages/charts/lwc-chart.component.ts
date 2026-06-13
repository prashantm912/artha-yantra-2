import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MarketStore } from '../../stores/market.store';
import { ArthaYantraDatafeed, type Bar, type RawCandle } from './datafeed/datafeed-core';
import { LwcChartBinding } from './lwc-chart-binding';

/**
 * `LwcChartComponent` (Phase 40, A13): the `/charts` main chart. Wires the library-agnostic
 * datafeed core + `LwcChartBinding` + the MarketStore live tick feed (refcounted track/untrack).
 * The component holds NO lightweight-charts types — those are confined to the binding (E-9). Initial
 * load → setData; live ticks → aggregate → updateLast; scroll-to-left-edge → page back → prepend.
 */
@Component({
  selector: 'ay-lwc-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .chart {
      width: 100%;
      height: 100%;
      min-height: 26rem;
    }
  `,
  template: `<div #host class="chart"></div>`,
})
export class LwcChartComponent {
  readonly symbol = input.required<string>(); // canonical EXCHANGE:TRADINGSYMBOL
  readonly interval = input<string>('1d');

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private readonly http = inject(HttpClient);
  private readonly market = inject(MarketStore);
  private readonly datafeed = new ArthaYantraDatafeed(this.candleFetch());

  private binding?: LwcChartBinding;
  private currentKey?: string;
  private lastBar: Bar | null = null;
  private loadingOlder = false;

  constructor() {
    afterNextRender(() => this.init());

    // reload on symbol/interval change (after the binding exists)
    effect(() => {
      const s = this.symbol();
      const i = this.interval();
      if (this.binding) {
        this.reload(s, i);
      }
    });

    // live tick → aggregate → update the in-progress bar
    effect(() => {
      const key = this.symbol();
      const tick = this.market.ticks()[key];
      if (this.binding && tick && key === this.currentKey) {
        const { bar } = this.datafeed.aggregate(
          {
            lastPrice: tick.lastPrice,
            cumulativeDayVolume: tick.cumulativeDayVolume,
            timestampMs: Date.parse(tick.timestamp),
          },
          this.interval(),
          this.lastBar,
        );
        this.lastBar = bar;
        this.binding.updateLast(bar);
      }
    });

    inject(DestroyRef).onDestroy(() => {
      if (this.currentKey) {
        this.market.untrack([this.currentKey]);
      }
      this.binding?.remove();
    });
  }

  private init(): void {
    try {
      this.binding = new LwcChartBinding(this.host().nativeElement, this.interval(), {
        precision: 2,
        minMove: 0.01,
      });
      this.binding.onPageBack((oldestMs) => this.pageBack(oldestMs));
      this.reload(this.symbol(), this.interval());
    } catch {
      // no real canvas (jsdom) — the chart simply does not render
    }
  }

  private reload(symbol: string, interval: string): void {
    if (!this.binding) {
      return;
    }
    if (symbol !== this.currentKey) {
      if (this.currentKey) {
        this.market.untrack([this.currentKey]);
      }
      this.market.track([symbol]);
      this.currentKey = symbol;
    }
    this.binding.setInterval(interval);
    const { exchange, tradingsymbol } = split(symbol);
    void this.datafeed
      .fetchBars(exchange, tradingsymbol, interval, Date.now(), 300)
      .then((bars) => {
        this.lastBar = bars.length ? bars[bars.length - 1] : null;
        this.binding?.setData(bars);
      });
  }

  private pageBack(oldestMs: number): void {
    if (this.loadingOlder || !this.binding) {
      return;
    }
    this.loadingOlder = true;
    const { exchange, tradingsymbol } = split(this.symbol());
    void this.datafeed
      .fetchBars(exchange, tradingsymbol, this.interval(), oldestMs, 200)
      .then((older) => {
        this.binding?.prepend(older);
        this.loadingOlder = false;
      })
      .catch(() => (this.loadingOlder = false));
  }

  private candleFetch() {
    return (
      exchange: string,
      tradingsymbol: string,
      interval: string,
      fromIso: string,
      toIso: string,
      limit: number,
    ): Promise<RawCandle[]> => {
      const params = new HttpParams()
        .set('exchange', exchange)
        .set('tradingsymbol', tradingsymbol)
        .set('interval', interval)
        .set('from', fromIso)
        .set('to', toIso)
        .set('limit', limit);
      return firstValueFrom(
        this.http.get<{ items: RawCandle[] }>('/api/v1/market/candles', { params }),
      ).then((r) => r.items ?? []);
    };
  }
}

function split(canonical: string): { exchange: string; tradingsymbol: string } {
  const sep = canonical.indexOf(':');
  return { exchange: canonical.slice(0, sep), tradingsymbol: canonical.slice(sep + 1) };
}

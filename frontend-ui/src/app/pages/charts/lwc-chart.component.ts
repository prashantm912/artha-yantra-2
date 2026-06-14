import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MarketStore } from '../../stores/market.store';
import { ArthaYantraDatafeed, type Bar, type RawCandle } from './datafeed/datafeed-core';
import { LwcChartBinding, type CrosshairSnapshot } from './lwc-chart-binding';
import { ChartIndicatorsService, type IndicatorMeta } from './chart-indicators.service';
import { type OverlayConfig } from './chart-state.store';
import {
  paperTradeMarks,
  signalMarks,
  tradeMarks,
  type MarkDto,
  type PaperTradeLike,
  type SignalLike,
  type TradeLike,
} from './datafeed/marks';
import { floorBucketMs } from './datafeed/timestamp';

const SPAN_MS: Record<string, number> = {
  '1m': 60_000,
  '5m': 300_000,
  '15m': 900_000,
  '1h': 3_600_000,
  '1d': 86_400_000,
  '1w': 604_800_000,
};

/**
 * `LwcChartComponent` (Phases 40 + 40C, A13): the `/charts` main chart. Wires the library-agnostic
 * datafeed core + `LwcChartBinding` + live ticks (refcounted MarketStore.track), plus 40C overlays
 * (engine-computed series — never client-side math, S7), the crosshair OHLCV+overlay legend, and the
 * accessible "View as table". The component holds NO lightweight-charts types (E-9).
 */
@Component({
  selector: 'ay-lwc-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .frame {
      position: relative;
      height: 100%;
      min-height: 26rem;
    }
    .chart {
      width: 100%;
      height: 100%;
    }
    .legend {
      position: absolute;
      top: 0.4rem;
      left: 0.6rem;
      z-index: 3;
      font-variant-numeric: tabular-nums;
      font-size: 0.78rem;
      color: var(--ay-text-muted);
      pointer-events: none;
      background: color-mix(in srgb, var(--ay-surface-1) 70%, transparent);
      padding: 0.2rem 0.4rem;
      border-radius: 6px;
    }
    .legend .ovl {
      color: var(--ay-accent);
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-variant-numeric: tabular-nums;
      font-size: 0.82rem;
    }
    th,
    td {
      border-bottom: 1px solid var(--ay-border);
      padding: 0.2rem 0.5rem;
      text-align: right;
    }
    th:first-child,
    td:first-child {
      text-align: left;
    }
    .tablewrap {
      max-height: 100%;
      overflow: auto;
    }
  `,
  template: `
    @if (showTable()) {
      <div class="tablewrap">
        <table>
          <caption class="ay-sr-only">
            Chart data (OHLCV and overlay values)
          </caption>
          <thead>
            <tr>
              <th scope="col">Time</th>
              <th scope="col">Open</th>
              <th scope="col">High</th>
              <th scope="col">Low</th>
              <th scope="col">Close</th>
              <th scope="col">Volume</th>
              @for (k of tableColumns(); track k) {
                <th scope="col">{{ k }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (row of tableSnapshot(); track row['time']) {
              <tr>
                <td>{{ row['time'].slice(0, 19).replace('T', ' ') }}</td>
                <td>{{ row['open'] }}</td>
                <td>{{ row['high'] }}</td>
                <td>{{ row['low'] }}</td>
                <td>{{ row['close'] }}</td>
                <td>{{ row['volume'] }}</td>
                @for (k of tableColumns(); track k) {
                  <td>{{ row[k] }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    } @else {
      <div class="frame">
        <div #host class="chart"></div>
        @if (legend(); as l) {
          <div class="legend" aria-hidden="true">
            O {{ l.open }} H {{ l.high }} L {{ l.low }} C {{ l.close }} V {{ l.volume }}
            @for (e of overlayEntries(l); track e.key) {
              <span class="ovl"> · {{ e.key }} {{ e.value }}</span>
            }
          </div>
        }
      </div>
    }
  `,
})
export class LwcChartComponent {
  readonly symbol = input.required<string>(); // canonical EXCHANGE:TRADINGSYMBOL
  readonly interval = input<string>('1d');
  readonly overlays = input<OverlayConfig[]>([]);
  readonly showTable = input<boolean>(false);
  // Phase 40A deep-link marks: a backtest run's trades (by runId) OR signals (by symbol window)
  readonly runId = input<string | null>(null);
  readonly focusTradeId = input<number | null>(null);
  readonly focusSignalId = input<number | null>(null);

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private readonly http = inject(HttpClient);
  private readonly market = inject(MarketStore);
  private readonly indicators = inject(ChartIndicatorsService);
  private readonly router = inject(Router);
  private readonly datafeed = new ArthaYantraDatafeed(this.candleFetch());

  protected readonly legend = signal<CrosshairSnapshot | null>(null);
  protected readonly tableSnapshot = signal<Record<string, string>[]>([]);
  protected readonly tableColumns = computed(() => this.overlays().map((o) => o.id));

  private binding?: LwcChartBinding;
  private meta = new Map<string, IndicatorMeta>();
  private currentKey?: string;
  private lastBar: Bar | null = null;
  private loadingOlder = false;
  private loadedFromMs = 0;
  private loadedToMs = 0;
  private overlayTimer?: ReturnType<typeof setTimeout>;
  private marksReAnchored = false;

  constructor() {
    afterNextRender(() => this.init());

    effect(() => {
      const s = this.symbol();
      const i = this.interval();
      if (this.binding) {
        this.reload(s, i);
      }
    });

    // overlay set changed → reconcile against the chart
    effect(() => {
      const configs = this.overlays();
      if (this.binding && this.meta.size) {
        this.reconcileOverlays(configs);
      }
    });

    // accessible table snapshot when toggled on
    effect(() => {
      if (this.showTable()) {
        this.tableSnapshot.set(this.binding?.tableRows() ?? []);
      }
    });

    // deep-link marks: re-render when the run/signal focus changes
    effect(() => {
      this.runId();
      this.focusTradeId();
      this.focusSignalId();
      this.symbol();
      if (this.binding && this.loadedFromMs) {
        this.marksReAnchored = false;
        this.refreshMarks();
      }
    });

    // live tick → aggregate → update; a bucket rollover refreshes overlays (closed-bar refresh)
    effect(() => {
      const key = this.symbol();
      const tick = this.market.ticks()[key];
      if (this.binding && tick && key === this.currentKey) {
        const { bar, rollover } = this.datafeed.aggregate(
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
        if (rollover) {
          this.loadedToMs = Date.now();
          this.scheduleOverlayRefresh();
        }
      }
    });

    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.overlayTimer);
      if (this.currentKey) {
        this.market.untrack([this.currentKey]);
      }
      this.binding?.remove();
    });
  }

  protected overlayEntries(l: CrosshairSnapshot): { key: string; value: number }[] {
    return Object.entries(l.overlays).map(([key, value]) => ({ key, value }));
  }

  private init(): void {
    try {
      this.binding = new LwcChartBinding(this.host().nativeElement, this.interval(), {
        precision: 2,
        minMove: 0.01,
      });
      this.binding.onPageBack((oldestMs) => this.pageBack(oldestMs));
      this.binding.subscribeCrosshair((snap) => this.legend.set(snap));
      this.binding.subscribeMarkClick((mark) => this.onMarkClick(mark));
      void this.indicators.registry().then((list) => {
        this.meta = new Map(list.map((m) => [m.id, m]));
        this.reconcileOverlays(this.overlays());
      });
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
    this.loadedToMs = Date.now();
    void this.datafeed
      .fetchBars(exchange, tradingsymbol, interval, this.loadedToMs, 300)
      .then((bars) => {
        this.lastBar = bars.length ? bars[bars.length - 1] : null;
        this.loadedFromMs = bars.length ? bars[0].time : this.loadedToMs;
        this.binding?.setData(bars);
        this.refreshOverlays();
        this.refreshMarks();
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
        if (older.length) {
          this.loadedFromMs = older[0].time;
          this.refreshOverlays(); // overlay back-fill over the extended range
        }
        this.loadingOlder = false;
      })
      .catch(() => (this.loadingOlder = false));
  }

  private reconcileOverlays(configs: OverlayConfig[]): void {
    this.refreshOverlays(configs);
  }

  /** Fetch + render trade marks (by runId) or signal marks (by symbol); center on a deep-link focus. */
  private refreshMarks(): void {
    if (!this.binding || !this.loadedFromMs) {
      return;
    }
    const runId = this.runId();
    if (runId) {
      const params = new HttpParams().set('limit', 1000);
      void firstValueFrom(
        this.http.get<{ items: TradeLike[] }>(`/api/v1/backtests/${runId}/trades`, { params }),
      )
        .then((r) => {
          const trades = r.items ?? [];
          const focusId = this.focusTradeId();
          this.binding?.setMarks(
            tradeMarks(trades, this.interval(), this.loadedFromMs, this.loadedToMs),
            focusId != null,
          );
          if (focusId != null) {
            this.centerOnTrade(trades, focusId);
          }
        })
        .catch(() => undefined);
    } else {
      const sym = this.symbol();
      const signals$ = firstValueFrom(
        this.http.get<{ items: SignalLike[] }>('/api/v1/signals', {
          params: new HttpParams().set('limit', 200),
        }),
      );
      // FP-67: closed paper trades on this symbol feed the same chart-marks surface
      const paper$ = firstValueFrom(
        this.http.get<{ items: PaperTradeLike[] }>('/api/v1/paper/trades', {
          params: new HttpParams().set('symbol', sym).set('limit', 200),
        }),
      ).catch(() => ({ items: [] as PaperTradeLike[] }));
      void Promise.all([signals$, paper$])
        .then(([s, p]) => {
          const focusSig = this.focusSignalId();
          const marks = [
            ...signalMarks(s.items ?? [], sym, this.interval(), focusSig ?? undefined),
            ...paperTradeMarks(p.items ?? [], this.interval(), this.loadedFromMs, this.loadedToMs),
          ];
          this.binding?.setMarks(marks, focusSig != null);
          const focus =
            focusSig != null ? marks.find((m) => m.id === `s${focusSig}-entry`) : undefined;
          if (focus) {
            this.binding?.centerOn(focus.timeMs);
          }
        })
        .catch(() => undefined);
    }
  }

  private centerOnTrade(trades: TradeLike[], focusId: number): void {
    const trade = trades.find((t) => t.seq === focusId);
    if (!trade) {
      return;
    }
    const focusMs = floorBucketMs(Date.parse(trade.entryTs), this.interval());
    if (focusMs >= this.loadedFromMs && focusMs <= this.loadedToMs) {
      this.binding?.centerOn(focusMs);
    } else if (!this.marksReAnchored) {
      // the trade predates the recent window — load bars around it first, then re-mark + center
      this.marksReAnchored = true;
      const span = SPAN_MS[this.interval()] ?? SPAN_MS['1d'];
      const { exchange, tradingsymbol } = split(this.symbol());
      this.loadedToMs = focusMs + 40 * span;
      void this.datafeed
        .fetchBars(exchange, tradingsymbol, this.interval(), this.loadedToMs, 300)
        .then((bars) => {
          this.loadedFromMs = bars.length ? bars[0].time : this.loadedToMs;
          this.lastBar = bars.length ? bars[bars.length - 1] : null;
          this.binding?.setData(bars);
          this.refreshOverlays();
          this.refreshMarks();
          this.binding?.centerOn(focusMs);
        })
        .catch(() => undefined);
    }
  }

  private onMarkClick(mark: MarkDto): void {
    const runId = this.runId();
    if (runId && mark.id.startsWith('t')) {
      void this.router.navigate(['/backtests', runId]);
    } else if (mark.id.startsWith('s')) {
      void this.router.navigate(['/signals']);
    }
  }

  private scheduleOverlayRefresh(): void {
    clearTimeout(this.overlayTimer);
    this.overlayTimer = setTimeout(() => this.refreshOverlays(), 400);
  }

  /** Fetch every active overlay's engine series for the loaded range and (re)render it. */
  private refreshOverlays(configs: OverlayConfig[] = this.overlays()): void {
    if (!this.binding || !this.meta.size || !this.loadedFromMs) {
      return;
    }
    for (const config of configs) {
      const meta = this.meta.get(config.id);
      if (!meta) {
        continue;
      }
      void this.indicators
        .series(
          config.id,
          this.symbol(),
          this.interval(),
          this.loadedFromMs,
          this.loadedToMs,
          config.params,
        )
        .then((series) => {
          const first = series[0];
          if (first) {
            this.binding?.setOverlay(config.id, meta.render, meta.pane, first.points);
          }
        })
        .catch(() => undefined);
    }
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

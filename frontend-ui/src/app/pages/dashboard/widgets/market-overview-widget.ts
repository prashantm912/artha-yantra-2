import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { compareDecimal, formatDecimal } from '../../../core/decimal';
import { MarketStore } from '../../../stores/market.store';
import { SparklineComponent, type SparkPoint } from '../../../shared/sparkline';
import { PulseDirective } from '../../../shared/pulse.directive';

interface IndexDef {
  key: string;
  label: string;
}

interface Candle {
  bucket: string;
  close: string;
}

/** The pinned indices the MarketOverview card row tracks (INDIA VIX added per FP-14). */
const INDICES: IndexDef[] = [
  { key: 'NSE:NIFTY 50', label: 'NIFTY 50' },
  { key: 'NSE:NIFTY BANK', label: 'BANK NIFTY' },
  { key: 'BSE:SENSEX', label: 'SENSEX' },
  { key: 'NSE:INDIA VIX', label: 'INDIA VIX' },
];

/** Bounded re-poll for an index whose daily isn't cached on first paint (D1). */
const MAX_WARM_RETRIES = 5;
const WARM_RETRY_MS = 3000;

/**
 * MarketOverview widget (E-2, Phase 35): NIFTY / BANK NIFTY / SENSEX / INDIA VIX cards with the
 * live last price (from the MarketStore tick map), a sign-only ▲/▼ vs the prior close (no float
 * arithmetic on prices — E-6), and a daily lightweight-charts sparkline. Degrades to "—" for any
 * index the mock feed does not tick.
 */
@Component({
  selector: 'ay-market-overview-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SparklineComponent, PulseDirective],
  styles: `
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr));
      gap: 0.6rem;
    }
    .card {
      border: 1px solid var(--ay-border);
      border-radius: 8px;
      padding: 0.6rem 0.7rem;
      background: var(--ay-surface-2);
      display: grid;
      grid-template-rows: auto auto 2.4rem;
      gap: 0.2rem;
    }
    .label {
      font-size: 0.75rem;
      color: var(--ay-text-muted);
    }
    .price {
      font-variant-numeric: tabular-nums;
      font-weight: 600;
      font-size: 1.05rem;
      display: flex;
      gap: 0.35rem;
      align-items: baseline;
    }
    .up {
      color: var(--ay-bull);
    }
    .down {
      color: var(--ay-bear);
    }
  `,
  template: `
    <div class="grid">
      @for (idx of indices; track idx.key) {
        <div class="card">
          <span class="label">{{ idx.label }}</span>
          <span class="price" [class.up]="dir(idx.key) > 0" [class.down]="dir(idx.key) < 0">
            <span [ayPulse]="price(idx.key)">{{ price(idx.key) }}</span>
            @if (dir(idx.key) > 0) {
              <span aria-label="up">▲</span>
            } @else if (dir(idx.key) < 0) {
              <span aria-label="down">▼</span>
            }
          </span>
          @if (series()[idx.key]; as pts) {
            <ay-sparkline [points]="pts" [tone]="dir(idx.key) < 0 ? 'bear' : 'bull'" />
          } @else {
            <span></span>
          }
        </div>
      }
    </div>
  `,
})
export class MarketOverviewWidget {
  protected readonly indices = INDICES;
  private readonly market = inject(MarketStore);
  private readonly http = inject(HttpClient);

  protected readonly series = signal<Record<string, SparkPoint[]>>({});
  private readonly prevClose = signal<Record<string, string>>({});
  // indices whose daily never warmed after the bounded retries (e.g. BSE:SENSEX — not in the
  // instrument universe). Rendered as an explicit "n/a" instead of a perpetual "—" (D2).
  private readonly unavailable = signal<Set<string>>(new Set());

  // live tick prices, reactive to the store
  private readonly ticks = computed(() => this.market.ticks());

  private readonly retryTimers = new Set<ReturnType<typeof setTimeout>>();

  constructor() {
    const keys = INDICES.map((i) => i.key);
    this.market.track(keys);
    inject(DestroyRef).onDestroy(() => {
      this.market.untrack(keys);
      this.retryTimers.forEach(clearTimeout);
    });
    const to = new Date();
    const from = new Date(to.getTime() - 70 * 86_400_000);
    for (const idx of INDICES) {
      this.loadIndex(idx, from, to, 0);
    }
  }

  /**
   * Fetch one index's daily series. The cache-first warm on market-data can still be in flight on a
   * cold cache, so an empty response is re-polled a BOUNDED number of times (D1) — a freshly-warmed
   * index then fills in without a manual reload. Bounded so an index with no data settles on "—"
   * instead of polling forever.
   */
  private loadIndex(idx: IndexDef, from: Date, to: Date, attempt: number): void {
    const sep = idx.key.indexOf(':');
    const params = new HttpParams()
      .set('exchange', idx.key.slice(0, sep))
      .set('tradingsymbol', idx.key.slice(sep + 1))
      .set('interval', '1d')
      .set('from', from.toISOString())
      .set('to', to.toISOString())
      .set('limit', 60);
    this.http.get<{ items: Candle[] }>('/api/v1/market/candles', { params }).subscribe({
      next: (resp) => {
        const items = resp.items ?? [];
        if (items.length === 0) {
          if (attempt < MAX_WARM_RETRIES) {
            const timer = setTimeout(() => {
              this.retryTimers.delete(timer);
              this.loadIndex(idx, from, to, attempt + 1);
            }, WARM_RETRY_MS);
            this.retryTimers.add(timer);
          } else {
            this.unavailable.update((s) => new Set(s).add(idx.key));
          }
          return;
        }
        // decimal-string→number conversion happens HERE, at the chart render boundary only (E-10.2)
        const pts: SparkPoint[] = items.map((c) => ({
          time: Math.floor(Date.parse(c.bucket) / 1000),
          value: Number(c.close),
        }));
        this.series.update((s) => ({ ...s, [idx.key]: pts }));
        const prior = items[items.length - 2]?.close ?? items[items.length - 1].close;
        this.prevClose.update((p) => ({ ...p, [idx.key]: prior }));
      },
      error: () => undefined,
    });
  }

  /** Live last price, falling back to the latest daily close; "—" when neither is known. */
  protected price(key: string): string {
    const live = this.ticks()[key]?.lastPrice;
    if (live) {
      return formatDecimal(live, 2);
    }
    const pts = this.series()[key];
    if (pts && pts.length) {
      return formatDecimal(String(pts[pts.length - 1].value), 2);
    }
    return this.unavailable().has(key) ? 'n/a' : '—';
  }

  /** Direction sign vs the prior close — sign comparison only, never float arithmetic (E-6). */
  protected dir(key: string): number {
    const live = this.ticks()[key]?.lastPrice;
    const prev = this.prevClose()[key];
    if (!live || !prev) {
      return 0;
    }
    return compareDecimal(live, prev);
  }
}

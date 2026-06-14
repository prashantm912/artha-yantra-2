import { computed, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { subtractDecimal } from '../core/decimal';
import { WsClientService } from '../core/ws-client.service';

/** One contract leg from GET /market/futures/term-structure (prices = decimal strings). */
export interface ContractLeg {
  tradingsymbol: string;
  expiry: string;
  daysToExpiry: number;
  ltp: string;
  oi: number | null;
  volume: number | null;
  basisAbsolute: string;
  basisAnnualized: string;
}

/** The term structure for one underlying. */
export interface TermStructure {
  underlying: string;
  spot: string;
  state: 'CONTANGO' | 'BACKWARDATION';
  calendarSpread: string | null;
  stale: boolean;
  asOf: string;
  contracts: ContractLeg[];
}

/** One row from the `oi_buildup` screener preset. */
export interface ScreenerRow {
  exchange: string;
  tradingsymbol: string;
  latestClose: string;
  pastClose: string;
  value: string;
  avgVolume: string | null;
  distanceFromHigh52w: string | null;
  label: string;
}

interface Candle {
  bucket: string;
  close: string;
}

/** A FUT−spot basis point for the history chart. */
export interface BasisPoint {
  bucket: string;
  basis: string;
}

/** One OI-buildup tile (the four server-classified quadrants). */
export interface BuildupTile {
  key: string;
  title: string;
  rows: ScreenerRow[];
}

const BUILDUP_TILES: { key: string; title: string }[] = [
  { key: 'LONG_BUILDUP', title: 'Long buildup' },
  { key: 'SHORT_BUILDUP', title: 'Short buildup' },
  { key: 'LONG_UNWINDING', title: 'Long unwinding' },
  { key: 'SHORT_COVERING', title: 'Short covering' },
];

interface FuturesState {
  underlying: string;
  term: TermStructure | null;
  futCandles: Candle[];
  spotCandles: Candle[];
  buildup: ScreenerRow[];
  loading: boolean;
}

/** Aligns FUT vs spot 1d closes by bucket and computes the absolute basis (exact decimal math). */
export function alignBasis(fut: Candle[], spot: Candle[]): BasisPoint[] {
  const spotByBucket = new Map(spot.map((c) => [c.bucket, c.close]));
  const points: BasisPoint[] = [];
  for (const bar of fut) {
    const spotClose = spotByBucket.get(bar.bucket);
    if (spotClose) {
      points.push({ bucket: bar.bucket, basis: subtractDecimal(bar.close, spotClose) });
    }
  }
  return points;
}

/** Groups screener rows into the four OI-buildup tiles (classification stays server-side). */
export function groupBuildup(rows: ScreenerRow[]): BuildupTile[] {
  return BUILDUP_TILES.map((tile) => ({
    ...tile,
    rows: rows.filter((row) => row.label === tile.key),
  }));
}

/** Severity for the contango/backwardation chip. */
export function stateSeverity(state: string | undefined): 'info' | 'warn' {
  return state === 'BACKWARDATION' ? 'warn' : 'info';
}

/**
 * FuturesStore (F-42A): the futures workbench — term-structure cards, FUT−spot basis history,
 * calendar-spread/rollover cost and OI-buildup heat tiles. REST-only (term-structure + candles +
 * the `oi_buildup` screener preset); it NEVER subscribes to the tick firehose. Derived values come
 * from {@link withComputed} (decimal math via the decimal utility, never `parseFloat`).
 */
export const FuturesStore = signalStore(
  { providedIn: 'root' },
  withState<FuturesState>({
    underlying: 'NIFTY 50',
    term: null,
    futCandles: [],
    spotCandles: [],
    buildup: [],
    loading: false,
  }),
  withComputed((store) => ({
    basisSeries: computed(() => alignBasis(store.futCandles(), store.spotCandles())),
    buildupTiles: computed(() => groupBuildup(store.buildup())),
    stale: computed(() => store.term()?.stale ?? false),
  })),
  withMethods((store, http = inject(HttpClient)) => {
    function candles(exchange: string, tradingsymbol: string) {
      const to = new Date();
      const from = new Date(to.getTime() - 200 * 86_400_000);
      const params = new HttpParams()
        .set('exchange', exchange)
        .set('tradingsymbol', tradingsymbol)
        .set('interval', '1d')
        .set('from', from.toISOString())
        .set('to', to.toISOString())
        .set('limit', 250);
      return http.get<{ items: Candle[] }>('/api/v1/market/candles', { params });
    }
    return {
      /** Term structure → then the front-contract basis history. */
      load(): void {
        patchState(store, { loading: true });
        http
          .get<TermStructure>('/api/v1/market/futures/term-structure', {
            params: new HttpParams().set('underlying', store.underlying()),
          })
          .subscribe({
            next: (term) => {
              patchState(store, { term, loading: false });
              const front = term.contracts[0];
              if (front) {
                this.loadBasis(front.tradingsymbol);
              }
            },
            error: () => patchState(store, { term: null, loading: false }),
          });
      },

      loadBasis(futSymbol: string): void {
        candles('NFO', futSymbol).subscribe({
          next: (res) => patchState(store, { futCandles: res.items ?? [] }),
          error: () => patchState(store, { futCandles: [] }),
        });
        candles('NSE', store.underlying()).subscribe({
          next: (res) => patchState(store, { spotCandles: res.items ?? [] }),
          error: () => patchState(store, { spotCandles: [] }),
        });
      },

      loadBuildup(): void {
        http
          .get<{ items: ScreenerRow[] }>('/api/v1/market/screener', {
            params: new HttpParams()
              .set('preset', 'oi_buildup')
              .set('window', '1d')
              .set('limit', 100),
          })
          .subscribe({
            next: (res) => patchState(store, { buildup: res.items ?? [] }),
            error: () => patchState(store, { buildup: [] }),
          });
      },

      setUnderlying(underlying: string): void {
        patchState(store, { underlying, futCandles: [], spotCandles: [] });
        this.load();
        this.loadBuildup();
      },
    };
  }),
  withHooks({
    onInit(store) {
      const ws = inject(WsClientService);
      ws.reconnects$.subscribe(() => {
        store.load();
        store.loadBuildup();
      });
      store.load();
      store.loadBuildup();
    },
  }),
);

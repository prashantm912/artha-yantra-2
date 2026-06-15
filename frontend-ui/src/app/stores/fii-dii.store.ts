import { inject } from '@angular/core';
import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { SILENCE_ERROR_TOAST } from '../core/error.interceptor';

// --- wire types (decimal money fields are JSON strings; contract/qty fields are numbers) ---

/** GET /api/v1/market/fii-dii/cash item. */
export interface FiiDiiRow {
  tradeDate: string;
  category: string;
  buyValue: string | null;
  sellValue: string | null;
  netValue: string | null;
}

/** GET /api/v1/market/fii-dii/participant-oi item (subset of the 16 wire columns we render). */
export interface ParticipantOiRow {
  tradeDate: string;
  clientType: string;
  futureIndexLong: number;
  futureIndexShort: number;
  optionIndexCallLong: number;
  optionIndexPutLong: number;
  totalLongContracts: number;
  totalShortContracts: number;
}

/** GET /api/v1/market/fii-dii/long-short item (derived FII index-futures ratio). */
export interface LongShortRow {
  tradeDate: string;
  fiiLong: number;
  fiiShort: number;
  ratio: string | null;
}

interface FiiDiiState {
  from: string;
  to: string | null;
  cash: FiiDiiRow[];
  participant: ParticipantOiRow[];
  longShort: LongShortRow[];
  loading: boolean;
}

const SILENT = new HttpContext().set(SILENCE_ERROR_TOAST, true);

/** Today's date as yyyy-MM-dd in IST (en-CA renders ISO-ordered). */
function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
}

/**
 * FiiDiiStore: index-level NSE EOD reads (FII/DII cash, participant-wise OI, FII long/short). These
 * are date-ranged (not the symbol/interval control-bar model), so they own their own date selection.
 * An empty result (no ingest yet for the range) is a normal state — the error toast is suppressed.
 */
export const FiiDiiStore = signalStore(
  { providedIn: 'root' },
  withState<FiiDiiState>(() => ({
    from: todayIst(),
    to: null,
    cash: [],
    participant: [],
    longShort: [],
    loading: false,
  })),
  withMethods((store, http = inject(HttpClient)) => {
    let gen = 0;

    function range(): HttpParams {
      let p = new HttpParams().set('from', store.from());
      const to = store.to();
      if (to) {
        p = p.set('to', to);
      }
      return p;
    }

    return {
      setFrom(from: string): void {
        patchState(store, { from });
      },
      setTo(to: string | null): void {
        patchState(store, { to });
      },

      /** Loads all three FII/DII feeds for the current date range in one shot. */
      load(): void {
        if (!store.from()) {
          return;
        }
        const g = ++gen;
        const p = range();
        patchState(store, { loading: true });
        http
          .get<{
            items: FiiDiiRow[];
          }>('/api/v1/market/fii-dii/cash', { params: p, context: SILENT })
          .subscribe({
            next: (res) => g === gen && patchState(store, { cash: res.items ?? [] }),
            error: () => g === gen && patchState(store, { cash: [] }),
          });
        http
          .get<{ items: ParticipantOiRow[] }>('/api/v1/market/fii-dii/participant-oi', {
            params: p,
            context: SILENT,
          })
          .subscribe({
            next: (res) => g === gen && patchState(store, { participant: res.items ?? [] }),
            error: () => g === gen && patchState(store, { participant: [] }),
          });
        http
          .get<{ items: LongShortRow[] }>('/api/v1/market/fii-dii/long-short', {
            params: p,
            context: SILENT,
          })
          .subscribe({
            next: (res) =>
              g === gen && patchState(store, { longShort: res.items ?? [], loading: false }),
            error: () => g === gen && patchState(store, { longShort: [], loading: false }),
          });
      },
    };
  }),
);

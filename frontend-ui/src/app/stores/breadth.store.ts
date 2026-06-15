import { inject } from '@angular/core';
import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { SILENCE_ERROR_TOAST } from '../core/error.interceptor';

// --- wire types (delivery %, prices, change are JSON strings; counts are numbers) ---

export interface BreadthSummary {
  tradeDate: string;
  advances: number;
  declines: number;
  unchanged: number;
  total: number;
  avgDeliveryPct: string | null;
}

export interface DeliveryRow {
  symbol: string;
  deliveryPct: string | null;
  close: string | null;
  pctChange: string | null;
}

export interface Breadth {
  summary: BreadthSummary;
  topDelivery: DeliveryRow[];
  asOf: string | null;
}

interface BreadthState {
  date: string;
  breadth: Breadth | null;
  loading: boolean;
}

const SILENT = new HttpContext().set(SILENCE_ERROR_TOAST, true);

/** Today's date as yyyy-MM-dd in IST (en-CA renders ISO-ordered). */
function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
}

/**
 * BreadthStore: single-date NSE EQ breadth (advance/decline + delivery%-leaders). 422 DATA_GAP when
 * no bhavcopy exists for the date is a normal empty state (toast suppressed -> breadth stays null).
 */
export const BreadthStore = signalStore(
  { providedIn: 'root' },
  withState<BreadthState>(() => ({ date: todayIst(), breadth: null, loading: false })),
  withMethods((store, http = inject(HttpClient)) => {
    let gen = 0;
    return {
      setDate(date: string): void {
        patchState(store, { date });
      },

      load(): void {
        if (!store.date()) {
          return;
        }
        const g = ++gen;
        patchState(store, { loading: true });
        http
          .get<Breadth>('/api/v1/market/breadth', {
            params: new HttpParams().set('date', store.date()),
            context: SILENT,
          })
          .subscribe({
            next: (breadth) => g === gen && patchState(store, { breadth, loading: false }),
            error: () => g === gen && patchState(store, { breadth: null, loading: false }),
          });
      },
    };
  }),
);

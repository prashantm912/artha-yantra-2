import { inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { type Subscription } from 'rxjs';
import { ConflationBuffer } from '../core/conflation';
import { WsClientService } from '../core/ws-client.service';

/** Instrument-master row as `/api/v1/instruments/search` returns it. */
export interface Instrument {
  exchange: string;
  tradingsymbol: string;
  instrumentToken: number;
  name: string;
  segment: string;
  instrumentType: string;
  underlyingExchange?: string | null;
  underlyingTradingsymbol?: string | null;
  expiry?: string | null;
  strike?: string | null;
  tickSize: string;
  lotSize: number;
  active: boolean;
}

/** Conflated last-tick row (prices arrive as decimal STRINGS — platform convention). */
export interface NormalizedTick {
  exchange: string;
  tradingsymbol: string;
  lastPrice: string;
  cumulativeDayVolume: number;
  openInterest: number;
  timestamp: string;
  seq: number;
}

/** `EXCHANGE:TRADINGSYMBOL` — the canonical key /ticks/latest uses and the tick map is keyed by. */
export function canonicalKey(exchange: string, tradingsymbol: string): string {
  return `${exchange}:${tradingsymbol}`;
}

interface MarketState {
  kiteSession: string | null;
  overall: string | null;
  searchResults: Instrument[];
  searching: boolean;
  ticks: Record<string, NormalizedTick>;
  dashboardLayout: Record<string, boolean>;
}

const LAYOUT_KEY = 'ay.dashboard';

/**
 * MarketStore (E-1): connection/Kite status from `/topic/system` deltas with the ONE allowed 10 s
 * fallback poll, plus the Stage-E additions — instrument search, refcounted per-symbol tick
 * subscriptions (the tick map fed via rAF conflation), and the dashboard widget layout in
 * localStorage (E-2; no heavyweight drag-grid in v1).
 */
export const MarketStore = signalStore(
  { providedIn: 'root' },
  withState<MarketState>({
    kiteSession: null,
    overall: null,
    searchResults: [],
    searching: false,
    ticks: {},
    dashboardLayout: {},
  }),
  withMethods((store, http = inject(HttpClient), ws = inject(WsClientService)) => {
    // Persistent across method calls: one live tick sub per symbol, refcounted across consumers
    // (many widgets can watch the same symbol), + the rAF conflation buffer.
    const tickSubs = new Map<string, Subscription>();
    const refs = new Map<string, number>();
    const buffer = new ConflationBuffer<NormalizedTick>((batch) =>
      patchState(store, { ticks: { ...store.ticks(), ...Object.fromEntries(batch) } }),
    );
    return {
      refresh(): void {
        http
          .get<{ overall?: string; kite?: { session?: string } }>('/api/v1/system/status')
          .subscribe({
            next: (status) =>
              patchState(store, {
                overall: status.overall ?? null,
                kiteSession: status.kite?.session ?? null,
              }),
            error: () => undefined,
          });
      },

      /** Instrument-master search (TopBar autocomplete + chart/runner instrument pickers). */
      search(q: string): void {
        patchState(store, { searching: true });
        http
          .get<Instrument[]>('/api/v1/instruments/search', {
            params: new HttpParams().set('q', q).set('limit', 20),
          })
          .subscribe({
            next: (results) =>
              patchState(store, { searchResults: results ?? [], searching: false }),
            error: () => patchState(store, { searching: false }),
          });
      },

      clearSearch(): void {
        patchState(store, { searchResults: [] });
      },

      /**
       * Add a refcount to each canonical key (EXCHANGE:SYMBOL); a key going 0→1 opens its
       * `ticks.{exchange}.{tradingsymbol}` subscription and seeds it from /ticks/latest. Consumers
       * pair every `track` with an `untrack` (on destroy) so shared symbols stay alive.
       */
      track(keys: string[]): void {
        const added: string[] = [];
        for (const key of keys) {
          const n = (refs.get(key) ?? 0) + 1;
          refs.set(key, n);
          if (n === 1) {
            const sep = key.indexOf(':');
            const exch = key.slice(0, sep);
            const sym = key.slice(sep + 1);
            const sub = ws.topic(`/topic/ticks.${exch}.${sym}`).subscribe((body) => {
              try {
                const t = JSON.parse(body) as NormalizedTick;
                buffer.set(canonicalKey(t.exchange, t.tradingsymbol), t);
              } catch {
                // unparseable tick frame — the seed/poll heals
              }
            });
            tickSubs.set(key, sub);
            added.push(key);
          }
        }
        if (added.length > 0) {
          http
            .get<Record<string, NormalizedTick>>('/api/v1/market/ticks/latest', {
              params: new HttpParams().set('symbols', added.join(',')),
            })
            .subscribe({
              next: (map) => patchState(store, { ticks: { ...store.ticks(), ...map } }),
              error: () => undefined,
            });
        }
      },

      /** Release a refcount per key; the last release (→0) tears the WS subscription down. */
      untrack(keys: string[]): void {
        for (const key of keys) {
          const n = (refs.get(key) ?? 0) - 1;
          if (n <= 0) {
            refs.delete(key);
            tickSubs.get(key)?.unsubscribe();
            tickSubs.delete(key);
          } else {
            refs.set(key, n);
          }
        }
      },

      /** Persist a widget's collapse state to the localStorage layout (E-2). */
      setWidgetCollapsed(id: string, collapsed: boolean): void {
        const layout = { ...store.dashboardLayout(), [id]: collapsed };
        patchState(store, { dashboardLayout: layout });
        try {
          localStorage.setItem(LAYOUT_KEY, JSON.stringify(layout));
        } catch {
          // private-mode / quota — collapse state simply does not persist
        }
      },
    };
  }),
  withHooks({
    onInit(store) {
      const ws = inject(WsClientService);
      try {
        const raw = localStorage.getItem(LAYOUT_KEY);
        if (raw) {
          patchState(store, { dashboardLayout: JSON.parse(raw) as Record<string, boolean> });
        }
      } catch {
        // ignore a corrupt layout blob
      }
      ws.topic('/topic/system').subscribe((body) => {
        try {
          const delta = JSON.parse(body) as { overall?: string; kite?: { session?: string } };
          patchState(store, {
            overall: delta.overall ?? store.overall(),
            kiteSession: delta.kite?.session ?? store.kiteSession(),
          });
        } catch {
          // non-JSON delta — the fallback poll heals
        }
      });
      store.refresh();
      setInterval(() => store.refresh(), 10_000);
    },
  }),
);

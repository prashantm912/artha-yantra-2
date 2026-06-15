import { DestroyRef, inject } from '@angular/core';
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

/** Reads a non-HttpOnly cookie (the XSRF token) for the keepalive unload DELETE that bypasses HttpClient. */
function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|;\\s*)' + name + '=([^;]+)'));
  return match ? match[1] : null;
}

interface MarketState {
  kiteSession: string | null;
  overall: string | null;
  searchResults: Instrument[];
  searching: boolean;
  underlyings: string[];
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
    underlyings: [],
    ticks: {},
    dashboardLayout: {},
  }),
  withMethods((store, http = inject(HttpClient), ws = inject(WsClientService)) => {
    // Persistent across method calls: one live tick sub per symbol, refcounted across consumers
    // (many widgets can watch the same symbol), + the rAF conflation buffer.
    const tickSubs = new Map<string, Subscription>();
    const refs = new Map<string, number>();
    // A UI-priority ticker subscription must be registered per watched symbol (D9): opening the WS
    // tick topic alone never makes the Kite ticker stream a non-pinned instrument. One subscriber
    // identity per app instance so the registry keeps the symbol live until THIS tab releases it.
    const subscriber = `ui:${Math.random().toString(36).slice(2, 12)}`;
    const buffer = new ConflationBuffer<NormalizedTick>((batch) =>
      patchState(store, { ticks: { ...store.ticks(), ...Object.fromEntries(batch) } }),
    );
    // Release every UI ticker hold when the page is torn down: ngOnDestroy does NOT run on a hard
    // tab-close/reload, so the in-app untrack DELETE never fires and the holds would leak. keepalive
    // lets the DELETE survive the unload; pagehide (not visibilitychange) avoids dropping a hold on
    // a mere tab switch. Bypasses HttpClient, so the XSRF header is attached by hand.
    const releaseAllOnUnload = (): void => {
      const xsrf = readCookie('XSRF-TOKEN');
      const headers: Record<string, string> = xsrf ? { 'X-XSRF-TOKEN': xsrf } : {};
      for (const key of refs.keys()) {
        const sep = key.indexOf(':');
        const params = new URLSearchParams({
          exchange: key.slice(0, sep),
          tradingsymbol: key.slice(sep + 1),
          subscriber,
        });
        void fetch(`/api/v1/market/subscriptions?${params.toString()}`, {
          method: 'DELETE',
          keepalive: true,
          credentials: 'same-origin',
          headers,
        });
      }
    };
    if (typeof window !== 'undefined') {
      window.addEventListener('pagehide', releaseAllOnUnload);
      inject(DestroyRef).onDestroy(() =>
        window.removeEventListener('pagehide', releaseAllOnUnload),
      );
    }
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

      /** Distinct F&O underlyings (index/stock) for the options/futures pickers. */
      loadUnderlyings(): void {
        http
          .get<{ exchange: string; tradingsymbol: string }[]>('/api/v1/instruments/underlyings')
          .subscribe({
            next: (rows) =>
              patchState(store, {
                underlyings: [...new Set((rows ?? []).map((r) => r.tradingsymbol))],
              }),
            error: () => undefined,
          });
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
            // Register the ticker subscription so the backend actually streams this symbol.
            http
              .post('/api/v1/market/subscriptions', {
                exchange: exch,
                tradingsymbol: sym,
                mode: 'quote',
                priority: 'ui',
                subscriber,
              })
              .subscribe({ next: () => undefined, error: () => undefined });
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
            const sep = key.indexOf(':');
            http
              .delete('/api/v1/market/subscriptions', {
                params: new HttpParams()
                  .set('exchange', key.slice(0, sep))
                  .set('tradingsymbol', key.slice(sep + 1))
                  .set('subscriber', subscriber),
              })
              .subscribe({ next: () => undefined, error: () => undefined });
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

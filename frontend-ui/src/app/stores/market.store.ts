import { inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { WsClientService } from '../core/ws-client.service';

interface MarketState {
  kiteSession: string | null;
  overall: string | null;
}

/**
 * Minimal MarketStore (C-2.22, Stage-C slice): connection/kite status from /topic/system deltas
 * with the ONE allowed low-frequency fallback poll (10 s against /api/v1/system/status,
 * Caffeine-cached 5 s server-side) healing missed deltas across reconnects.
 */
export const MarketStore = signalStore(
  { providedIn: 'root' },
  withState<MarketState>({ kiteSession: null, overall: null }),
  withMethods((store, http = inject(HttpClient)) => ({
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
  })),
  withHooks({
    onInit(store) {
      const ws = inject(WsClientService);
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

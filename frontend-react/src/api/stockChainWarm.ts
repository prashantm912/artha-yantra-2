// The on-demand stock-chain OI warm (audit §9.3, Wave 5): stocks have no standing interval-OI
// capture (the 2-min snapshot job covers the 6 indices) — a warm pulls one (stock, expiry, day)'s
// 1-minute Upstox bars into the snapshot store, after which every OI page serves the stock
// unchanged. POST starts a single-flight async fetch; GET polls it.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** GET/POST /market/options/stock-chain/warm — the warm's typed status. */
export interface StockChainWarmStatus {
  underlying: string;
  expiry: string;
  day: string;
  state: 'UNAVAILABLE' | 'IDLE' | 'RUNNING' | 'DONE' | 'FAILED';
  totalLegs: number;
  fetchedLegs: number;
  rowsInStore: number;
}

/** The captured indices (standing 2-min snapshot job) — everything else warms on demand. */
export function isCapturedIndex(name: string): boolean {
  return /NIFTY|SENSEX|BANKEX/i.test(name);
}

function params(name: string, expiry: string | null, date: string | null): string {
  const p = new URLSearchParams({ name });
  if (expiry) p.set('expiry', expiry);
  if (date) p.set('date', date);
  return p.toString();
}

/** Polls the warm status while enabled (2s while a warm runs, else on demand). */
export function useStockChainWarmStatus(
  name: string,
  expiry: string | null,
  date: string | null,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ['stock-chain-warm', name, expiry, date],
    queryFn: () =>
      apiFetch<StockChainWarmStatus>(`/market/options/stock-chain/warm?${params(name, expiry, date)}`, {
        silenceToast: true,
      }),
    enabled,
    refetchInterval: (q) => (q.state.data?.state === 'RUNNING' ? 2000 : false),
  });
}

/** Starts the warm; on completion the caller invalidates the OI queries so pages refill. */
export function useStartStockChainWarm() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, expiry, date }: { name: string; expiry: string | null; date: string | null }) =>
      apiFetch<StockChainWarmStatus>(
        `/market/options/stock-chain/warm?${params(name, expiry, date)}`,
        { method: 'POST', json: {} },
      ),
    onSuccess: (_status, vars) => {
      void qc.invalidateQueries({ queryKey: ['stock-chain-warm', vars.name] });
    },
  });
}

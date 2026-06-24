import { useQuery } from '@tanstack/react-query';
import { apiFetch, listItems } from './client.ts';
import type { MarketStatus, PreOpenSnapshot } from './types.ts';

// Pre-Open Market feed (§equity/pre-open-market). Two Upstox-backed reads: the per-exchange session
// phase (NSE/BSE/MCX) and the NSE pre-open snapshot (Nifty 50 / Bank / Fin / Sensex quotes). The
// {items} envelope is empty (and the snapshot phase is "UNKNOWN") when the Upstox status client is not
// wired (mock / analytics off) — the page renders its closed/empty state. Polled on a short interval so
// the session-phase banner flips through the pre-open window and the index LTPs stay live.

/** The per-exchange session phase (NSE/BSE/MCX). No params; polled while the page is open. */
export function useMarketStatus() {
  return useQuery({
    queryKey: ['market', 'market-status'],
    queryFn: async () => {
      const res = await apiFetch<{ items?: MarketStatus[] }>('/market/market-status', { silenceToast: true });
      return listItems(res);
    },
    staleTime: 20 * 1000,
    refetchInterval: 30 * 1000, // a 30s poll catches the pre-open phase flips (09:00–09:15 IST)
  });
}

/** The NSE pre-open snapshot — phase + the four index quotes. Polled while the page is open. */
export function usePreOpen() {
  return useQuery({
    queryKey: ['market', 'pre-open'],
    queryFn: () => apiFetch<PreOpenSnapshot>('/market/pre-open', { silenceToast: true }),
    staleTime: 20 * 1000,
    refetchInterval: 30 * 1000,
  });
}

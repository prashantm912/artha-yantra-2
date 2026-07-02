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

/** One preserved pre-open scan row (audit §9.4 Wave 5 — the full F&O stock scanner). */
export interface PreOpenScanRow {
  symbol: string;
  preOpenPrice: string | null;
  prevClose: string | null;
  change: string | null;
  changePct: string | null;
  prevDayBreak: 'H' | 'L' | null;
}

/** GET /market/equity/pre-open-scan — the captured scan for a day + the captured-session list. */
export interface PreOpenScan {
  date: string;
  items: PreOpenScanRow[];
  dates: string[];
}

/**
 * The preserved full-scanner read: every F&O stock's ~09:09:30 pre-open capture vs prev close +
 * the prev-day H/L break — reviewable all day and across sessions (`date` = history mode).
 */
export function usePreOpenScan(date: string | null) {
  return useQuery({
    queryKey: ['market', 'pre-open-scan', date],
    queryFn: () => {
      const p = new URLSearchParams();
      if (date) p.set('date', date);
      const qs = p.toString();
      return apiFetch<PreOpenScan>(`/market/equity/pre-open-scan${qs ? `?${qs}` : ''}`, {
        silenceToast: true,
      });
    },
  });
}

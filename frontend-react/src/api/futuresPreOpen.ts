import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';
import type { FuturesPreOpen } from './types.ts';

// Futures Pre-Open Market feed (§futures/pre-open-market). One Upstox-backed read: the NSE session phase
// + the radar stock-future rows + the index rows, with the market advance/decline/unchanged counts.
// Rows are empty (phase "UNKNOWN") when the Upstox status client is not wired (mock / analytics off) —
// the page renders its closed/empty state. Polled on a short interval so the pre-open snapshot (published
// ~09:09 IST) refreshes while the page is open.

/** The Futures Pre-Open scan — stock + index rows + counts. No params; polled while the page is open. */
export function useFuturesPreOpen() {
  return useQuery({
    queryKey: ['market', 'futures', 'pre-open'],
    queryFn: () => apiFetch<FuturesPreOpen>('/market/futures/pre-open', { silenceToast: true }),
    staleTime: 20 * 1000,
    refetchInterval: 30 * 1000,
  });
}

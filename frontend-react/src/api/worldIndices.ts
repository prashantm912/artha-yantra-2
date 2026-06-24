import { useQuery } from '@tanstack/react-query';
import { apiFetch, listItems } from './client.ts';
import type { WorldIndex } from './types.ts';

// World Indices feed (§features/world-indices). Live global-index quotes from Upstox: the GLOBAL_INDEX
// (+ GLOBAL_INDICATOR) roster joined with its live LTP / day OHLC / net change / derived %change. The
// {items} envelope is empty when the Upstox global client is not wired (mock / analytics off) — the page
// renders its empty state. Refreshes on a short interval so the LTP column stays live (900s Upstox latency).

/** The live World Indices snapshot. No params; polled while the page is open. */
export function useWorldIndices() {
  return useQuery({
    queryKey: ['market', 'world-indices'],
    queryFn: async () => {
      const res = await apiFetch<{ items?: WorldIndex[] }>('/market/world-indices', { silenceToast: true });
      return listItems(res);
    },
    staleTime: 30 * 1000,
    refetchInterval: 60 * 1000, // global quotes carry a ~900s publication latency — a 60s poll is ample
  });
}

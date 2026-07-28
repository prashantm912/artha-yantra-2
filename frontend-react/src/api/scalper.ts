// Scalper-cockpit refinements data layer (Phase 4b): the live option chain (for the strike-ladder
// quick-pick that fills the order ticket) and the conflated last-tick price (live LTP on the ticket).
// Both endpoints already exist (the Angular options/charts pages used them) — FE-only, no BE change.

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export interface ChainLeg {
  /** The instrument master's exchange — the other half of the canonical key. Never guessed here. */
  exchange: string | null;
  tradingsymbol: string;
  ltp: string | null;
  oi: number | null;
}
export interface ChainStrike {
  strike: string;
  ce: ChainLeg;
  pe: ChainLeg;
}
export interface OptionChain {
  underlying: string;
  spot: string | null;
  rows: ChainStrike[];
}

/** The live option chain for the strike-ladder quick-pick (expiry omitted = nearest). */
export function useOptionChain(underlying: string, expiry: string | null, enabled: boolean) {
  return useQuery({
    queryKey: ['scalper-chain', underlying, expiry],
    queryFn: () => {
      const p = new URLSearchParams({ underlying });
      if (expiry) p.set('expiry', expiry);
      return apiFetch<OptionChain>(`/market/options/chain?${p.toString()}`);
    },
    enabled: enabled && !!underlying,
    refetchInterval: 5000,
  });
}

interface NormalizedTick {
  exchange: string;
  tradingsymbol: string;
  lastPrice: string;
  cumulativeDayVolume: number;
}

/** The conflated last tick for one canonical EXCHANGE:SYMBOL (live LTP on the ticket); null until ticked. */
export function useLatestTick(symbol: string, enabled: boolean) {
  return useQuery({
    queryKey: ['tick', symbol],
    queryFn: () => apiFetch<Record<string, NormalizedTick>>(`/market/ticks/latest?symbols=${encodeURIComponent(symbol)}`),
    enabled: enabled && symbol.includes(':'),
    refetchInterval: 3000,
    select: (m) => m[symbol] ?? null,
  });
}

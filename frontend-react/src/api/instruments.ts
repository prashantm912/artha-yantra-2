import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

// Instrument-master queries. Both return BARE arrays (not the {items} envelope) — see CLAUDE.md.

/** /instruments/underlyings returns {exchange, tradingsymbol} rows — the OI endpoints key on the name. */
interface UnderlyingRow {
  exchange: string;
  tradingsymbol: string;
}

/** Underlyings for the control-bar name select — mapped to the tradingsymbol `name` strings, deduped. */
export function useUnderlyings() {
  return useQuery({
    queryKey: ['instruments', 'underlyings'],
    queryFn: async () => {
      const rows = await apiFetch<UnderlyingRow[]>('/instruments/underlyings');
      return [...new Set(rows.map((r) => r.tradingsymbol))];
    },
    staleTime: 5 * 60_000,
  });
}

/** Expiries for an underlying (replaces SymbolContextStore.loadExpiries). */
export function useExpiries(name: string) {
  return useQuery({
    queryKey: ['instruments', 'expiries', name],
    queryFn: () => apiFetch<string[]>(`/instruments/${encodeURIComponent(name)}/expiries`),
    enabled: !!name,
    staleTime: 60_000,
  });
}

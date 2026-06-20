import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

// Instrument-master queries. Both return BARE arrays (not the {items} envelope) — see CLAUDE.md.

/** Underlyings for the control-bar name select. */
export function useUnderlyings() {
  return useQuery({
    queryKey: ['instruments', 'underlyings'],
    queryFn: () => apiFetch<string[]>('/instruments/underlyings'),
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

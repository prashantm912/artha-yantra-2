// Watchlists + screener cockpit data layer (master plan §20 parity, E-8). Named watchlists (create /
// add / remove items) over /api/v1/watchlists, the Phase-17 screener over /api/v1/market/screener, and
// the instrument-search typeahead (/instruments/search returns a BARE array — see CLAUDE.md). Live
// per-symbol tick prices are deferred (no tick bridge in React yet).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export interface WatchItem {
  exchange: string;
  tradingsymbol: string;
}
export interface WatchlistView {
  id: string;
  name: string;
  items: WatchItem[];
}
export interface ScreenerRow {
  exchange: string;
  tradingsymbol: string;
  latestClose: string;
  value: string;
  avgVolume?: string | null;
  label?: string | null;
}
export interface InstrumentHit {
  exchange: string;
  tradingsymbol: string;
  name?: string;
}

export const SCREENER_PRESETS = ['momentum', 'long_term', 'oi_buildup', 'rs_rank'] as const;

const KEY = 'watchlists';

export function useWatchlists() {
  return useQuery({
    queryKey: [KEY],
    queryFn: () => apiFetch<{ items: WatchlistView[] }>('/watchlists'),
  });
}

export function useCreateWatchlist() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => apiFetch<WatchlistView>('/watchlists', { method: 'POST', json: { name } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [KEY] }),
  });
}

export function useAddWatchItem() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, item }: { id: string; item: WatchItem }) =>
      apiFetch(`/watchlists/${id}/items`, { method: 'POST', json: item }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [KEY] }),
  });
}

export function useRemoveWatchItem() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, item }: { id: string; item: WatchItem }) =>
      apiFetch(`/watchlists/${id}/items`, { method: 'DELETE', json: item }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [KEY] }),
  });
}

export function useScreener(preset: string, enabled: boolean) {
  return useQuery({
    queryKey: ['screener', preset],
    queryFn: () => apiFetch<{ items: ScreenerRow[] }>(`/market/screener?preset=${preset}&limit=50`),
    enabled,
  });
}

/** Instrument typeahead for the add-to-watchlist picker (bare-array endpoint). */
export function useInstrumentSearch(q: string) {
  return useQuery({
    queryKey: ['instruments', 'search', q],
    queryFn: () => apiFetch<InstrumentHit[]>(`/instruments/search?q=${encodeURIComponent(q)}`),
    enabled: q.trim().length >= 2,
  });
}

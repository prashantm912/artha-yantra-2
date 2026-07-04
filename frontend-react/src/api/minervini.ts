// Minervini SEPA screener data layer (Track-1). The daily 8-gate Trend-Template + RS-rank screen
// over /api/v1/market/screener/minervini (persisted GET) + /run (recompute). Decimals cross the wire
// as JSON strings (see CLAUDE.md; render via lib/decimal).

import { useMutation, useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export interface MinerviniRow {
  symbol: string;
  exchange: string;
  close: string;
  sma50?: string | null;
  sma150?: string | null;
  sma200?: string | null;
  high52w?: string | null;
  low52w?: string | null;
  pctFromHigh?: string | null;
  pctAboveLow?: string | null;
  rsRank?: string | null;
  avgTurnover50?: string | null;
  freeFloatMcapCr?: string | null;
  freeFloatPct?: string | null;
  gates: boolean[];
  gatesPassed: number;
  passesAll: boolean;
  stage?: number | null;
}

export interface MinerviniScreen {
  items: MinerviniRow[];
  screenDate: string | null;
  coverage: number;
  limit: number;
  offset: number;
}

export interface ScreenParams {
  passesAllOnly: boolean;
  minRsRank: number;
  limit: number;
}

function qs(p: ScreenParams): string {
  return new URLSearchParams({
    passesAllOnly: String(p.passesAllOnly),
    minRsRank: String(p.minRsRank),
    limit: String(p.limit),
  }).toString();
}

/** The persisted daily screen (fast path). */
export function useMinerviniScreen(p: ScreenParams, enabled = true) {
  return useQuery({
    queryKey: ['minervini', p],
    queryFn: () => apiFetch<MinerviniScreen>(`/market/screener/minervini?${qs(p)}`),
    enabled,
  });
}

/** Recompute the screen now (POST /run). */
export function useRunMinervini() {
  return useMutation({
    mutationFn: (p: ScreenParams) =>
      apiFetch<MinerviniScreen>(`/market/screener/minervini/run?${qs(p)}`, { method: 'POST' }),
  });
}

// --- SEPA funnel three-list (MV-6.7): the day's passers ranked into the actionable triad. ---

export interface FunnelRow {
  symbol: string;
  close: string;
  rsRank?: string | null;
  stage?: number | null;
  isVcp: boolean;
  pivot?: string | null;
  footprint?: string | null;
  pctToPivot?: string | null; // (close - pivot) / pivot
}

export interface MinerviniFunnel {
  screenDate: string | null;
  immediatelyBuyable: FunnelRow[];
  onDeck: FunnelRow[];
  watch: FunnelRow[];
}

/** The SEPA funnel three-list (immediately-buyable / on-deck / watch) for the latest screen. */
export function useMinerviniFunnel(asOf?: string, enabled = true) {
  const suffix = asOf ? `?asOf=${asOf}` : '';
  return useQuery({
    queryKey: ['minervini-funnel', asOf ?? 'latest'],
    queryFn: () => apiFetch<MinerviniFunnel>(`/market/screener/minervini/funnel${suffix}`),
    enabled,
  });
}

// Minervini SEPA screener data layer (Track-1). The daily 8-gate Trend-Template + RS-rank screen
// over /api/v1/market/screener/minervini (persisted GET) + /run (recompute). Decimals cross the wire
// as JSON strings (see CLAUDE.md; render via lib/decimal).

import { useMutation, useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';
import type { MarketCandle } from './types.ts';

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

export interface Regime {
  regime: string; // FAVORABLE | NEUTRAL | HOSTILE
  advanceRatio?: string | null;
  sessions: number;
}

export interface MinerviniFunnel {
  screenDate: string | null;
  regime: Regime | null;
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

// --- Per-candidate analyzer (MV-4.4/5.5): the full Trend-Template + Stage + VCP geometry drill-down. ---

export interface CandidateGeometry {
  isVcp: boolean;
  footprint?: string | null; // Technical Footprint "40W 31/3 4T"
  pivot?: string | null; // the VCP buy trigger
  deepestPct?: string | null; // deepest contraction depth %
  tightestPct?: string | null; // tightest (final) contraction %
  contractionCount?: number | null;
  baseWeeks?: number | null;
  baseDurationDays?: number | null;
  volumeDryUp: boolean;
  shakeout: boolean;
  baseCount?: number | null;
  rejectReason?: string | null; // why the base is not a valid VCP
}

export interface MinerviniCandidate {
  symbol: string;
  exchange: string;
  screenDate: string | null;
  scanned: boolean; // false when the symbol was not in the persisted screen (analyzed on demand)
  close?: string | null;
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
  geometry: CandidateGeometry;
}

/** The per-candidate analyzer payload (persisted gates/stage/fundamentals + live VCP geometry). */
export function useMinerviniCandidate(symbol: string, asOf?: string, enabled = true) {
  const suffix = asOf ? `?asOf=${asOf}` : '';
  return useQuery({
    queryKey: ['minervini-candidate', symbol, asOf ?? 'latest'],
    queryFn: () =>
      apiFetch<MinerviniCandidate>(
        `/market/screener/minervini/candidate/${encodeURIComponent(symbol)}${suffix}`,
      ),
    enabled: enabled && !!symbol,
  });
}

/** Deep daily OHLCV (~`days` sessions) for the analyzer chart — enough to draw the 200-day MA + base. */
export function useDeepDailyCandles(symbol: string, exchange = 'NSE', days = 420, enabled = true) {
  return useQuery({
    queryKey: ['minervini-daily', exchange, symbol, days],
    queryFn: async () => {
      const to = new Date();
      // ~1.5 calendar days per trading session (weekends/holidays) so the store returns ≥`days` bars.
      const from = new Date(to.getTime() - days * 1.5 * 86_400_000);
      const p = new URLSearchParams({
        exchange,
        tradingsymbol: symbol,
        interval: '1d',
        from: from.toISOString(),
        to: to.toISOString(),
        limit: String(days + 40),
      });
      const res = await apiFetch<{ items: MarketCandle[] }>(`/market/candles?${p.toString()}`);
      return res.items;
    },
    enabled: enabled && !!symbol,
  });
}

// Manas Arora selection-screener data layer. The daily 6-criteria §4.1 selection filter + §1.2
// universe (within-25%-of-high) + §4.3 liquidity + §4.4 low-cap float over
// /api/v1/market/screener/manas-arora (persisted GET) + /run (recompute) + /funnel (the actionable
// three-list) + /candidate/{symbol} (per-symbol analyzer) + /swing-backtest (deep-history evidence).
// Decimals cross the wire as JSON strings (see CLAUDE.md; render via lib/decimal). Mirrors the
// sibling minervini.ts data layer.

import { useMutation, useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';
import type { MarketCandle } from './types.ts';

export interface ManasRow {
  symbol: string;
  exchange: string;
  close: string;
  sma50?: string | null;
  sma200?: string | null;
  high52w?: string | null;
  low52w?: string | null;
  avgVol20?: string | null;
  avgVol50?: string | null;
  turnover50?: string | null;
  withinHighPct?: string | null;
  aboveLowPct?: string | null;
  withinHigh: boolean;
  aboveSma50: boolean;
  liquidVolume: boolean;
  liquidDepth: boolean;
  lowCap: boolean;
  freeFloatMcapCr?: string | null;
  freeFloatPct?: string | null;
  gates: boolean[];
  gatesPassed: number;
  passesAll: boolean;
}

export interface ManasScreen {
  items: ManasRow[];
  screenDate: string | null;
  coverage: number;
  limit: number;
  offset: number;
}

export interface ManasScreenParams {
  passesAllOnly: boolean;
  limit: number;
}

function qs(p: ManasScreenParams): string {
  return new URLSearchParams({
    passesAllOnly: String(p.passesAllOnly),
    limit: String(p.limit),
  }).toString();
}

/** The persisted daily selection screen (fast path). */
export function useManasScreen(p: ManasScreenParams, enabled = true) {
  return useQuery({
    queryKey: ['manas-arora', p],
    queryFn: () => apiFetch<ManasScreen>(`/market/screener/manas-arora?${qs(p)}`),
    enabled,
  });
}

/** Recompute the screen now (POST /run). */
export function useRunManas() {
  return useMutation({
    mutationFn: (p: ManasScreenParams) =>
      apiFetch<ManasScreen>(`/market/screener/manas-arora/run?${qs(p)}`, { method: 'POST' }),
  });
}

// --- Funnel three-list (§3.1 step 4): the day's passers ranked into the actionable triad. ---

export interface ManasFunnelRow {
  symbol: string;
  close: string;
  aboveLowPct?: string | null;
  setupType?: string | null; // 'vcp' | 'breakout' | null
  pivot?: string | null;
  footprint?: string | null;
  pctToPivot?: string | null; // (close - pivot) / pivot
}

export interface Regime {
  regime: string; // FAVORABLE | NEUTRAL | HOSTILE
  advanceRatio?: string | null;
  sessions: number;
}

export interface ManasFunnel {
  screenDate: string | null;
  regime: Regime | null;
  immediatelyBuyable: ManasFunnelRow[];
  onDeck: ManasFunnelRow[];
  watch: ManasFunnelRow[];
}

/** The funnel three-list (immediately-buyable / on-deck / watch) for the latest screen. */
export function useManasFunnel(asOf?: string, enabled = true) {
  const suffix = asOf ? `?asOf=${asOf}` : '';
  return useQuery({
    queryKey: ['manas-arora-funnel', asOf ?? 'latest'],
    queryFn: () => apiFetch<ManasFunnel>(`/market/screener/manas-arora/funnel${suffix}`),
    enabled,
  });
}

// --- Per-candidate analyzer: the 6 gates + universe/liquidity/float flags + vcp/breakout geometry. ---

export interface ManasSetupView {
  setupType: string; // 'vcp' | 'breakout'
  valid: boolean;
  footprint?: string | null;
  pivot?: string | null;
  baseWeeks?: number | null;
  rejectReason?: string | null;
}

export interface ManasCandidate {
  symbol: string;
  exchange: string;
  screenDate: string | null;
  scanned: boolean; // false when the symbol was not in the persisted screen (analyzed on demand)
  close?: string | null;
  sma50?: string | null;
  sma200?: string | null;
  high52w?: string | null;
  low52w?: string | null;
  withinHighPct?: string | null;
  aboveLowPct?: string | null;
  avgVol20?: string | null;
  avgVol50?: string | null;
  turnover50?: string | null;
  withinHigh: boolean;
  aboveSma50: boolean;
  liquidVolume: boolean;
  liquidDepth: boolean;
  lowCap: boolean;
  freeFloatMcapCr?: string | null;
  freeFloatPct?: string | null;
  gates: boolean[];
  gatesPassed: number;
  passesAll: boolean;
  setups: ManasSetupView[];
}

/** The per-candidate analyzer payload (persisted gates/flags/fundamentals + live vcp + breakout geometry). */
export function useManasCandidate(symbol: string, asOf?: string, enabled = true) {
  const suffix = asOf ? `?asOf=${asOf}` : '';
  return useQuery({
    queryKey: ['manas-arora-candidate', symbol, asOf ?? 'latest'],
    queryFn: () =>
      apiFetch<ManasCandidate>(
        `/market/screener/manas-arora/candidate/${encodeURIComponent(symbol)}${suffix}`,
      ),
    enabled: enabled && !!symbol,
  });
}

/** Deep daily OHLCV (~`days` sessions) for the analyzer chart — enough to draw the 200-day MA + base. */
export function useDeepDailyCandles(symbol: string, exchange = 'NSE', days = 420, enabled = true) {
  return useQuery({
    queryKey: ['manas-arora-daily', exchange, symbol, days],
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

// --- Swing backtest: the deep-history multi-variant comparison (technical / rs / turnover / pyramid). ---

export interface ManasYearReturn {
  year: number;
  returnPct: string;
  trades: number;
}

export interface ManasSetupStat {
  setup: string;
  trades: number;
  wins: number;
  losses: number;
  winRatePct: string;
  avgWinPct: string;
  avgLossPct: string;
  payoffRatio: string;
  expectancyPct: string;
  profitFactor: string;
  avgBarsHeld: string;
  bestTradePct: string;
  worstTradePct: string;
  longestHoldBars: number;
  shortestHoldBars: number;
  maxWinStreak: number;
  maxLossStreak: number;
  stopOutPct: string;
}

export interface ManasPortfolioStat {
  slots: number;
  totalReturnPct: string;
  cagrPct: string;
  maxDrawdownPct: string;
  sharpe: string;
  tradesTaken: number;
  tradesSkipped: number;
  avgExposurePct: string;
  months: number;
  positiveMonthsPct: string;
  bestMonthPct: string;
  worstMonthPct: string;
  avgMonthPct: string;
  annual: ManasYearReturn[];
}

export interface ManasReport {
  status: string; // idle | running | completed | failed
  variant?: string | null;
  fromDate?: string | null;
  runAt?: string | null;
  symbolsScanned: number;
  totalTrades: number;
  setups: ManasSetupStat[];
  portfolio?: ManasPortfolioStat | null;
  portfolioRsPriority?: ManasPortfolioStat | null;
  portfolioRsPriorityNet?: ManasPortfolioStat | null;
  note?: string | null;
}

export interface ManasSlotCell {
  slots: number;
  tradesTaken: number;
  tradesSkipped: number;
  grossCagrPct: string;
  netCagrPct: string;
  netDrawdownPct: string;
  netSharpe: string;
}

export interface ManasBacktestResult {
  status: string; // idle | running | completed | failed
  fromDate?: string | null;
  runAt?: string | null;
  variants: ManasReport[];
  slotSweep: ManasSlotCell[];
  note?: string | null;
}

/** The latest multi-variant swing-backtest comparison. */
export function useManasBacktestCompare(enabled = true) {
  return useQuery({
    queryKey: ['manas-arora-backtest-compare'],
    queryFn: () =>
      apiFetch<ManasBacktestResult>('/market/screener/manas-arora/swing-backtest/compare'),
    enabled,
  });
}

/** Trigger a fresh swing backtest (POST /swing-backtest?years=N). Returns the running/idle Report. */
export function useRunManasBacktest() {
  return useMutation({
    mutationFn: (years?: number) => {
      const suffix = years ? `?years=${years}` : '';
      return apiFetch<ManasReport>(`/market/screener/manas-arora/swing-backtest${suffix}`, {
        method: 'POST',
      });
    },
  });
}

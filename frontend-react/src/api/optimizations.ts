// Optimizer sweep cockpit data layer (master plan §20 parity, Phase 39). Ports the OptimizationsStore
// read + promote slice: the guard-aware leaderboard (`/best`, plateau|raw sort), all trials (every
// state — pruned/failed are FLAGGED not hidden), and promote → new draft. A live sweep stays fresh via
// a short refetchInterval (the Angular WS frame fan-out is a later enhancement).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export type SortMode = 'plateau' | 'raw';
export type TrialState = 'RUNNING' | 'COMPLETE' | 'PRUNED' | 'FAILED';

export const SWEEPS_PAGE_SIZE = 25;

export interface SweepObjective {
  metric?: string;
  direction?: string;
  fold_aggregation?: string;
  objectives?: Array<{ metric?: string; direction?: string }>;
}

/** Native optimizer sweep-list row (`GET /optimizations/jobs`), newest first. */
export interface SweepSummary {
  jobId: string;
  status: string;
  progress: number;
  error?: string | null;
  createdAt?: string | null;
  strategyId?: string | null;
  method?: string | null;
  objective?: SweepObjective | null;
  from?: string | null;
  to?: string | null;
}

export interface SweepsEnvelope {
  items: SweepSummary[];
  limit: number;
  offset: number;
}

/** The §D.4 walk-forward guard outputs for a fold run (absent for legacy/full-window trials). */
export interface GuardMetrics {
  dataHash?: string | null;
  foldsExcluded?: number | null;
  /** Regimes that traded across folds, canonical order (UP_QUIET/UP_TURBULENT/DOWN_QUIET/DOWN_TURBULENT). */
  regimesCovered: string[];
  /** Min / mean / max of the per-(fold, regime) OOS Sharpe (null when no regime traded). */
  regimeOosMin?: number | null;
  regimeOosMean?: number | null;
  regimeOosMax?: number | null;
}

/** One leaderboard row (`GET /optimizations/{id}/best`). */
export interface BestRow {
  trialNumber: number;
  params: Record<string, unknown>;
  objective: number;
  objectiveValues?: Record<string, number> | null;
  backtestRunId?: string | null;
  plateauObjective?: number;
  neighborCount?: number;
  /** Persisted fold guards; absent for legacy/full-window trials → "no fold guards" badge. */
  guardMetrics?: GuardMetrics | null;
}

/**
 * One non-dominated point on a multi-objective (`nsga2`) sweep's Pareto front, exposed additively
 * on `GET /optimizations/{id}/best` under `paretoFront` (AY-OPT-03: the front is surfaced, never
 * collapsed to the scalar `items` ranking). Carries objective values only — no fold-guard
 * enrichment. The FE may adopt this later; `nsga2` sweeps are latent today.
 */
export interface ParetoPoint {
  trialNumber: number;
  params: Record<string, unknown>;
  objectiveValues?: Record<string, number> | null;
  backtestRunId?: string | null;
}

/** One trial (`GET /optimizations/{id}/trials`) — all states; pruned/failed flagged, not hidden. */
export interface TrialRow {
  trialNumber: number;
  params: Record<string, unknown>;
  objectiveValues?: Record<string, number> | null;
  state: TrialState;
  backtestRunId?: string | null;
}

/** A train/test window bound (ISO timestamps). */
export interface FoldWindow {
  from: string;
  to: string;
}

/** Per-regime OOS aggregates for one fold (decimals as STRINGS; only labels that traded are present). */
export interface RegimeOosStat {
  sharpe: string | null;
  expectancy: string | null;
  tradeCount: number;
}

// The four canonical regime labels, in backtest-service RegimeLabel enum order. These MUST match the
// enum names the backtest fold serializer emits (regimeOos/regimeMix are keyed by RegimeLabel.name())
// — a BULL/RANGE/BEAR/CRASH alias set never intersects the real keys, leaving every regime column empty.
export const REGIME_LABELS = ['UP_QUIET', 'UP_TURBULENT', 'DOWN_QUIET', 'DOWN_TURBULENT'] as const;
export type RegimeLabel = (typeof REGIME_LABELS)[number];

/**
 * One fold of a walk-forward (or implicit 70/30) trial run (`GET
 * /optimizations/{id}/trials/{trial}/folds`). The metric catalogs carry decimals AS STRINGS;
 * `regimeOos` keys are a subset of {@link REGIME_LABELS} (only labels that actually traded).
 */
export interface TrialFold {
  fold: number;
  train: FoldWindow;
  test: FoldWindow;
  trainMetrics: Record<string, string | null>;
  oosMetrics: Record<string, string | null>;
  regimeMix?: Record<string, number>;
  regimeOos: Partial<Record<RegimeLabel, RegimeOosStat>>;
}

/**
 * The per-fold drill-down for one trial — resolved via its backtest_run_id. Returns `[]` for a
 * full-window trial (no walk-forward structure). Lazy: only fetched when a trial is selected.
 */
export function useTrialFolds(sweepId: string, trialNumber: number | null) {
  return useQuery({
    queryKey: ['sweep', sweepId, 'trial', trialNumber, 'folds'],
    queryFn: () => apiFetch<TrialFold[]>(`/optimizations/${sweepId}/trials/${trialNumber}/folds`),
    enabled: !!sweepId && trialNumber != null,
  });
}

const REFETCH_MS = 4000;

export function useSweeps(offset: number) {
  return useQuery({
    queryKey: ['sweeps', 'list', offset],
    queryFn: () =>
      apiFetch<SweepsEnvelope>(
        `/optimizations/jobs?limit=${SWEEPS_PAGE_SIZE}&offset=${offset}`,
      ),
    refetchInterval: REFETCH_MS,
  });
}

export function useSweepBest(sweepId: string, sort: SortMode) {
  return useQuery({
    queryKey: ['sweep', sweepId, 'best', sort],
    queryFn: () =>
      apiFetch<{
        metric?: string;
        items: BestRow[];
        // Additive nsga2-only fields (AY-OPT-03); absent for single-objective sweeps.
        multiObjective?: boolean;
        objectives?: Array<{ metric?: string; direction?: string }>;
        paretoFront?: ParetoPoint[];
      }>(`/optimizations/${sweepId}/best?sort=${sort}&top=50`),
    enabled: !!sweepId,
    refetchInterval: REFETCH_MS,
  });
}

export function useSweepTrials(sweepId: string) {
  return useQuery({
    queryKey: ['sweep', sweepId, 'trials'],
    queryFn: () => apiFetch<{ items: TrialRow[] }>(`/optimizations/${sweepId}/trials`),
    enabled: !!sweepId,
    refetchInterval: REFETCH_MS,
  });
}

/** Promote a trial → a new draft (minor bump) with provenance; yields the new version. */
export function usePromoteTrial(sweepId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (trialNumber: number) =>
      apiFetch<{ newVersion: string; strategyId: string }>(`/optimizations/${sweepId}/promote`, {
        method: 'POST',
        json: { trialId: trialNumber },
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['strategies'] }),
  });
}

export function paramStr(row: { params: Record<string, unknown> }): string {
  return Object.entries(row.params)
    .map(([k, v]) => `${k}=${v}`)
    .join('  ');
}

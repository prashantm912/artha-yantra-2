// Optimizer sweep cockpit data layer (master plan §20 parity, Phase 39). Ports the OptimizationsStore
// read + promote slice: the guard-aware leaderboard (`/best`, plateau|raw sort), all trials (every
// state — pruned/failed are FLAGGED not hidden), and promote → new draft. A live sweep stays fresh via
// a short refetchInterval (the Angular WS frame fan-out is a later enhancement).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export type SortMode = 'plateau' | 'raw';
export type TrialState = 'RUNNING' | 'COMPLETE' | 'PRUNED' | 'FAILED';

/** The §D.4 walk-forward guard outputs for a fold run (absent for legacy/full-window trials). */
export interface GuardMetrics {
  dataHash?: string | null;
  foldsExcluded?: number | null;
  /** Regimes that traded across folds, canonical order (BULL/RANGE/BEAR/CRASH). */
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

/** One trial (`GET /optimizations/{id}/trials`) — all states; pruned/failed flagged, not hidden. */
export interface TrialRow {
  trialNumber: number;
  params: Record<string, unknown>;
  objectiveValues?: Record<string, number> | null;
  state: TrialState;
  backtestRunId?: string | null;
}

const REFETCH_MS = 4000;

export function useSweepBest(sweepId: string, sort: SortMode) {
  return useQuery({
    queryKey: ['sweep', sweepId, 'best', sort],
    queryFn: () => apiFetch<{ metric?: string; items: BestRow[] }>(`/optimizations/${sweepId}/best?sort=${sort}&top=50`),
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

// Backtest/sweep cockpit data layer (master plan §20 parity, E-8/E-11). Ports Angular's BacktestsStore
// + JobsStore slice needed for the runner and the jobs monitor: submit a backtest / sweep (→ 202
// {jobId}), the Postgres-backed job list, and live progress from the `jobs.progress` topic (NO polling
// — a frame for an unknown job heals the list with one reload; reconnect re-fetches). Results / folds /
// montecarlo (C7) and the sweep trial explorer (C8) build on the same endpoints.

import { useEffect } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, apiFetch } from './client.ts';
import { wsClient } from '../lib/wsClient.ts';

export type JobKind = 'BACKTEST' | 'OPTIMIZATION' | 'TRIAL';
export type JobStatus = 'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'cancelling';

export interface JobDto {
  jobId: string;
  kind: JobKind;
  status: JobStatus;
  progress: number;
  createdAt: string;
  /** Originating strategy id (the UI maps it to a name); null on legacy rows. */
  strategyId?: string | null;
  /** The completed run's total return (plain decimal string); null until a run exists. */
  totalReturn?: string | null;
  /** The tested window [from, to] (a date or ISO datetime); the UI shows the date part as Start/End. */
  testFrom?: string | null;
  testTo?: string | null;
  parentJobId?: string | null;
  resultRef?: string | null;
  error?: string | null;
  bestSoFar?: number | string | null;
}

interface JobProgressFrame {
  jobId: string;
  status: JobStatus;
  progress: number;
  bestSoFar?: number | string | null;
}

export interface RunRequest {
  strategyId: string;
  /** Optional explicit strategy version (e.g. "1.0.1"); omitted ⇒ the backend pins latest published, else latest draft. */
  strategyVersion?: string;
  from: string;
  to: string;
  interval: string;
  initialCapital: string;
  seed: number;
}

export interface SweepRequest extends Omit<RunRequest, 'seed'> {
  method: string;
  maxTrials: number;
  objective: { metric: string; direction: string; fold_aggregation: string };
  constraints: { min_trades: number };
  seed: number;
}

const JOBS_KEY = 'jobs';
const JOBS_TOPIC = '/topic/jobs/stream';
/** Page size for the jobs list (offset pagination). */
export const JOBS_PAGE_SIZE = 25;

export const JOB_STATUSES = ['queued', 'running', 'completed', 'failed', 'cancelled'] as const;
export const INTERVALS = ['1m', '5m', '15m', '1h', '1d', '1w'] as const;
export const SWEEP_METHODS = ['grid', 'random', 'tpe', 'nsga2'] as const;
export const OBJECTIVE_METRICS = ['sharpe', 'cagr', 'totalReturn', 'sortino', 'maxDrawdown', 'winRate'] as const;
export const DIRECTIONS = ['maximize', 'minimize'] as const;
export const FOLD_AGGREGATIONS = ['mean', 'min', 'mean_minus_std'] as const;

export function useJobs(
  status: string | null,
  strategyId: string | null,
  offset: number,
  sortBy?: string | null,
  sortDir?: 'asc' | 'desc' | null,
  strategyIds?: string | null,
) {
  return useQuery({
    queryKey: [JOBS_KEY, 'list', status, strategyId, offset, sortBy ?? null, sortDir ?? null, strategyIds ?? null],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(JOBS_PAGE_SIZE), offset: String(offset) });
      if (status) params.set('status', status);
      if (strategyId) params.set('strategyId', strategyId);
      if (sortBy) params.set('sortBy', sortBy);
      if (sortDir) params.set('sortDir', sortDir);
      if (strategyIds) params.set('strategyIds', strategyIds);
      return apiFetch<{ items: JobDto[] }>(`/backtests/jobs?${params.toString()}`);
    },
  });
}

/** Live progress from `/topic/jobs/stream`: patch the matching row; an unknown job heals via reload. */
export function useJobsLive(
  status: string | null,
  strategyId: string | null,
  offset: number,
  sortBy?: string | null,
  sortDir?: 'asc' | 'desc' | null,
  strategyIds?: string | null,
) {
  const qc = useQueryClient();
  useEffect(() => {
    const key = [JOBS_KEY, 'list', status, strategyId, offset, sortBy ?? null, sortDir ?? null, strategyIds ?? null];
    const merge = (body: string) => {
      let f: JobProgressFrame;
      try {
        f = JSON.parse(body) as JobProgressFrame;
      } catch {
        return;
      }
      qc.setQueryData<{ items: JobDto[] }>(key, (prev) => {
        if (!prev) return prev;
        const idx = prev.items.findIndex((j) => j.jobId === f.jobId);
        if (idx < 0) {
          void qc.invalidateQueries({ queryKey: key });
          return prev;
        }
        const items = prev.items.slice();
        items[idx] = {
          ...items[idx],
          status: f.status,
          progress: f.progress,
          ...(f.bestSoFar != null ? { bestSoFar: f.bestSoFar } : {}),
        };
        return { items };
      });
    };
    const offTopic = wsClient.topic(JOBS_TOPIC, merge);
    const offReconnect = wsClient.onReconnect(() => qc.invalidateQueries({ queryKey: [JOBS_KEY] }));
    return () => {
      offTopic();
      offReconnect();
    };
  }, [qc, status, strategyId, offset, sortBy, sortDir, strategyIds]);
}

export function useSubmitRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: RunRequest) =>
      apiFetch<{ jobId: string; status: string }>('/backtests/run', { method: 'POST', json: body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [JOBS_KEY] }),
  });
}

export function useSubmitSweep() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: SweepRequest) =>
      apiFetch<{ jobId: string; status: string }>('/optimizations/run', { method: 'POST', json: body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [JOBS_KEY] }),
  });
}

/** Cancel: 204 ⇒ cancelled (was queued); 202 {status:cancelling} ⇒ cancelling (was running). */
export function useCancelJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (jobId: string) => {
      const res = await apiFetch<{ status?: string } | undefined>(`/backtests/jobs/${jobId}`, {
        method: 'DELETE',
      });
      const status: JobStatus = res?.status === 'cancelling' ? 'cancelling' : 'cancelled';
      return { jobId, status };
    },
    onSuccess: ({ jobId, status }) =>
      qc.setQueriesData<{ items: JobDto[] }>({ queryKey: [JOBS_KEY] }, (prev) =>
        prev ? { items: prev.items.map((j) => (j.jobId === jobId ? { ...j, status } : j)) } : prev,
      ),
  });
}

/** The jobs LIST omits resultRef — resolve it lazily from the job detail (for the results link). */
export async function fetchResultRef(jobId: string): Promise<string | null> {
  const job = await apiFetch<{ resultRef?: string | null }>(`/backtests/jobs/${jobId}`);
  return job.resultRef ?? null;
}

// --- results page slice (PR-C7) — results are keyed by the RUN id (resultRef), not the jobId ---

/** One downsampled curve point (`ts` ISO, `value` decimal string). */
export interface CurvePoint {
  ts: string;
  value: string;
}

export interface BacktestResults {
  metrics: Record<string, string | number | null | string[]>;
  equityCurve: CurvePoint[];
  drawdownCurve?: CurvePoint[] | null;
  benchmarkCurve?: CurvePoint[] | null;
  dataHash?: string;
  seed?: number;
  premiumSource?: string;
  caveats?: string[];
  exchange?: string | null;
  tradingsymbol?: string | null;
  universeChecksum?: string | null;
  /** Originating strategy id (the header maps it to a name); null on legacy rows. */
  strategyId?: string | null;
  /** When the run completed (ISO) — the "date when it was run". */
  ranAt?: string | null;
}

export interface TradeRow {
  seq: number;
  side: 'LONG' | 'SHORT';
  qty: number;
  entryTs: string;
  entryPrice: string;
  exitTs?: string | null;
  exitPrice?: string | null;
  pnl: string;
  pnlPct: string;
  exitReason: string;
  barsHeld: number;
  touchBasis?: string | null;
  contributions?: Record<string, unknown> | null;
  stopLoss?: string | null;
  takeProfit?: string | null;
}

export interface FoldRow {
  fold: { index: number; trainFrom: string; trainTo: string; testFrom: string; testTo: string };
  trainMetrics: Record<string, string | number | null>;
  oosMetrics: Record<string, string | number | null>;
  regimeMix?: Record<string, number> | null;
}

export interface MonteCarloSummary {
  n: number;
  trades: number;
  insufficientSample: boolean;
  equityBands: { step: number[]; p5: string[]; p50: string[]; p95: string[] };
  drawdownDistribution: { p5: string; p50: string; p95: string; mean: string };
  riskOfRuin: string;
}

export function useBacktestResults(id: string) {
  return useQuery({
    queryKey: ['backtest', id, 'results'],
    queryFn: () => apiFetch<BacktestResults>(`/backtests/${id}/results`),
    enabled: !!id,
  });
}

export function useBacktestTrades(id: string) {
  return useQuery({
    queryKey: ['backtest', id, 'trades'],
    // Bounded cap so the client-side exit-reason breakdown sees the whole run (a tail past 1000 is
    // labelled "loaded trades" in the UI rather than silently dropped from the aggregation).
    queryFn: () => apiFetch<{ items: TradeRow[] }>(`/backtests/${id}/trades?limit=1000&offset=0`),
    enabled: !!id,
  });
}

/** Walk-forward folds — [] when the run had no walk-forward (the endpoint returns []). */
export function useBacktestFolds(id: string) {
  return useQuery({
    queryKey: ['backtest', id, 'folds'],
    queryFn: async () => {
      try {
        return await apiFetch<FoldRow[]>(`/backtests/${id}/folds`);
      } catch {
        return [];
      }
    },
    enabled: !!id,
  });
}

/** Up to 6 runs' results for the comparison matrix (`?ids=a,b,c`); returns [{id, data}]. */
export function useCompareResults(ids: string[]) {
  const capped = ids.slice(0, 6);
  return useQueries({
    queries: capped.map((id) => ({
      queryKey: ['backtest', id, 'results'],
      queryFn: () => apiFetch<BacktestResults>(`/backtests/${id}/results`),
      enabled: !!id,
    })),
    combine: (results) =>
      capped
        .map((id, i) => ({ id, data: results[i].data }))
        .filter((r): r is { id: string; data: BacktestResults } => !!r.data),
  });
}

export interface OiAttributionBucket {
  trend: number;
  label: string;
  count: number;
  wins: number;
  winRate: string | null;
  totalPnl: string;
  avgPnl: string | null;
}

export interface OiAttributionTradeRow {
  seq: number;
  tradingsymbol: string;
  entryTs: string;
  bucket: string;
  trend: number;
  trendLabel: string;
  net: number | null;
  pnl: string;
  win: boolean;
}

export interface OiAttribution {
  underlying: string | null;
  interval: string;
  tradeCount: number;
  tradesAttributed: number;
  tradesNoData: number;
  sessionsCovered: number;
  sessionsUncovered: number;
  oiDerived?: boolean;
  caveat: string | null;
  buckets: OiAttributionBucket[];
  trades: OiAttributionTradeRow[];
}

/** OI-confluence attribution: trades bucketed by the Connecting-Dots trend at each entry. */
export function useOiAttribution(id: string, interval: string) {
  return useQuery({
    queryKey: ['backtest', id, 'oi-attribution', interval],
    queryFn: () =>
      apiFetch<OiAttribution>(`/backtests/${id}/oi-attribution?interval=${interval}`),
    enabled: !!id,
  });
}

/** Monte Carlo summary — null when the run is unknown (404) or had no trades (422). */
export function useBacktestMonteCarlo(id: string) {
  return useQuery({
    queryKey: ['backtest', id, 'montecarlo'],
    queryFn: async () => {
      try {
        return await apiFetch<MonteCarloSummary>(`/backtests/${id}/montecarlo`);
      } catch (err) {
        if (err instanceof ApiError && (err.status === 404 || err.status === 422)) return null;
        throw err;
      }
    },
    enabled: !!id,
  });
}

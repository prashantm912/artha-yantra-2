// Backtest/sweep cockpit data layer (master plan §20 parity, E-8/E-11). Ports Angular's BacktestsStore
// + JobsStore slice needed for the runner and the jobs monitor: submit a backtest / sweep (→ 202
// {jobId}), the Postgres-backed job list, and live progress from the `jobs.progress` topic (NO polling
// — a frame for an unknown job heals the list with one reload; reconnect re-fetches). Results / folds /
// montecarlo (C7) and the sweep trial explorer (C8) build on the same endpoints.

import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';
import { wsClient } from '../lib/wsClient.ts';

export type JobKind = 'BACKTEST' | 'OPTIMIZATION' | 'TRIAL';
export type JobStatus = 'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'cancelling';

export interface JobDto {
  jobId: string;
  kind: JobKind;
  status: JobStatus;
  progress: number;
  createdAt: string;
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

export const JOB_STATUSES = ['queued', 'running', 'completed', 'failed', 'cancelled'] as const;
export const INTERVALS = ['1m', '5m', '15m', '1h', '1d', '1w'] as const;
export const SWEEP_METHODS = ['grid', 'random', 'tpe', 'nsga2'] as const;
export const OBJECTIVE_METRICS = ['sharpe', 'cagr', 'totalReturn', 'sortino', 'maxDrawdown', 'winRate'] as const;
export const DIRECTIONS = ['maximize', 'minimize'] as const;
export const FOLD_AGGREGATIONS = ['mean', 'min', 'mean_minus_std'] as const;

export function useJobs(status: string | null) {
  return useQuery({
    queryKey: [JOBS_KEY, 'list', status],
    queryFn: () => {
      const params = new URLSearchParams({ limit: '50', offset: '0' });
      if (status) params.set('status', status);
      return apiFetch<{ items: JobDto[] }>(`/backtests/jobs?${params.toString()}`);
    },
  });
}

/** Live progress from `/topic/jobs/stream`: patch the matching row; an unknown job heals via reload. */
export function useJobsLive(status: string | null) {
  const qc = useQueryClient();
  useEffect(() => {
    const merge = (body: string) => {
      let f: JobProgressFrame;
      try {
        f = JSON.parse(body) as JobProgressFrame;
      } catch {
        return;
      }
      qc.setQueryData<{ items: JobDto[] }>([JOBS_KEY, 'list', status], (prev) => {
        if (!prev) return prev;
        const idx = prev.items.findIndex((j) => j.jobId === f.jobId);
        if (idx < 0) {
          void qc.invalidateQueries({ queryKey: [JOBS_KEY, 'list', status] });
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
  }, [qc, status]);
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

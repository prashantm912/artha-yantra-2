// Dashboard cockpit data layer (master plan §20 parity, E-2). The at-a-glance grid reuses the
// signals + paper hooks; this module adds the two dashboard-only reads: the aggregated system status
// (worst-of Redis rollup — overall health, kite session, market phase, job counts) and the recent
// backtest/sweep jobs. Both poll on a short interval so the tiles stay live without a WS subscription.

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export interface SystemStatus {
  overall: 'UP' | 'DEGRADED' | string;
  services: { name: string; status: string }[];
  kite: { session: string; ticker: string; lastTickAgeMs: number | null; rateBudget: number | null };
  market: { phase: 'OPEN' | 'PRE_OPEN' | 'CLOSED' | string };
  jobs: { queued: number; running: number };
  asOf: string;
}

export interface JobDto {
  jobId: string;
  kind?: string;
  status: string;
  progress: number;
  strategyId?: string;
  resultRef?: string;
  createdAt?: string;
}

const STATUS_REFETCH_MS = 5000;
const JOBS_REFETCH_MS = 4000;

export function useSystemStatus() {
  return useQuery({
    queryKey: ['system', 'status'],
    queryFn: () => apiFetch<SystemStatus>('/system/status'),
    refetchInterval: STATUS_REFETCH_MS,
  });
}

/** Recent backtest/sweep jobs; the dashboard tile filters to the in-flight ones. */
export function useRecentJobs() {
  return useQuery({
    queryKey: ['jobs', 'recent'],
    queryFn: () => apiFetch<{ items: JobDto[] }>('/backtests/jobs?limit=20'),
    refetchInterval: JOBS_REFETCH_MS,
  });
}

export const JOB_ACTIVE_STATES = ['queued', 'running', 'cancelling'];

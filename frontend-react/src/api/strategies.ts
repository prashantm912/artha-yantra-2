// Strategy-registry cockpit data layer (master plan §20 parity, E-1/E-11). Ports the read + lifecycle
// slice of Angular's StrategiesStore: the strategy list (with each row's last-backtest summary for the
// sparkline), the immutable version timeline, the structured diff, the publish stress-window advisory,
// and the publish / rollback / archive / notification-toggle / delete mutations. The Monaco editor
// slice (validate / save / create / schema) lands with PR-C5.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** Strategy list-row summary (`GET /strategies`). */
export interface StrategySummary {
  id: string;
  slug: string;
  name: string;
  currentVersion?: string;
  publishedVersion?: string | null;
  currentVersionId?: string | null;
  publishedVersionId?: string | null;
  status: string;
  tags: string[];
  updatedAt?: string;
  notificationsEnabled?: boolean;
  notificationChannel?: string | null;
}

/** Latest-backtest summary for a strategy version (`GET /backtests/summary`). */
export interface BacktestSummary {
  strategyVersionId: string;
  runId: string;
  sharpe: string | null;
  totalReturn: string | null;
  maxDrawdown: string | null;
  completedAt?: string;
  equity: string[];
}

/** One row of the immutable version timeline (`GET /{id}/versions`). */
export interface VersionRow {
  version: string;
  status: string;
  checksum: string;
  author?: string;
  notes?: string | null;
  createdAt?: string;
}

/** One structured diff op (`GET /{id}/diff`): path: before → after. */
export interface DiffOp {
  path: string;
  op: 'add' | 'change' | 'remove';
  before: string | null;
  after: string | null;
}
export interface DiffResult {
  structured: DiffOp[];
  yamlFrom: string;
  yamlTo: string;
}

export const STRATEGY_STATUSES = ['draft', 'published', 'archived'] as const;
export const NOTIFY_CHANNELS = ['NTFY', 'TELEGRAM'] as const;

const KEY = 'strategies';

export function useStrategies(q: string, status: string | null) {
  return useQuery({
    queryKey: [KEY, 'list', q, status],
    queryFn: () => {
      const params = new URLSearchParams();
      if (q) params.set('q', q);
      if (status) params.set('status', status);
      const qs = params.toString();
      return apiFetch<{ items: StrategySummary[] }>(`/strategies${qs ? `?${qs}` : ''}`);
    },
  });
}

/** The last-backtest summary per version id (for the list sparkline + Sharpe column). */
export function useBacktestSummaries(versionIds: string[]) {
  const ids = [...new Set(versionIds)].sort();
  return useQuery({
    queryKey: [KEY, 'summaries', ids.join(',')],
    queryFn: () =>
      apiFetch<{ items: BacktestSummary[] }>(
        `/backtests/summary?strategyVersionIds=${encodeURIComponent(ids.join(','))}`,
      ),
    enabled: ids.length > 0,
    select: (page) => {
      const map: Record<string, BacktestSummary> = {};
      for (const item of page.items ?? []) map[item.strategyVersionId] = item;
      return map;
    },
  });
}

export function useStrategyDetail(id: string) {
  return useQuery({
    queryKey: ['strategy', id, 'detail'],
    queryFn: () => apiFetch<{ id: string; name: string; status: string; version: string }>(`/strategies/${id}`),
    enabled: !!id,
  });
}

export function useStrategyVersions(id: string) {
  return useQuery({
    queryKey: ['strategy', id, 'versions'],
    queryFn: () => apiFetch<{ items: VersionRow[] }>(`/strategies/${id}/versions`),
    enabled: !!id,
  });
}

export function useStrategyDiff(id: string, from: string, to: string) {
  return useQuery({
    queryKey: ['strategy', id, 'diff', from, to],
    queryFn: () =>
      apiFetch<DiffResult>(`/strategies/${id}/diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    enabled: !!id && !!from && !!to && from !== to,
  });
}

/** Advisory CLEAN stress window (publish dialog) — never blocks publish (E-13). */
export function useStressWindow(id: string, enabled: boolean) {
  return useQuery({
    queryKey: ['strategy', id, 'stress-window'],
    queryFn: () => apiFetch<{ from: string; to: string }>(`/backtests/stress-window?strategyId=${id}`),
    enabled: !!id && enabled,
  });
}

function useInvalidate(id?: string) {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: [KEY] });
    if (id) void qc.invalidateQueries({ queryKey: ['strategy', id] });
  };
}

export function usePublish(id: string) {
  const invalidate = useInvalidate(id);
  return useMutation({
    mutationFn: (body: { targetVersion?: string; notes?: string }) =>
      apiFetch<{ version: string; status: string }>(`/strategies/${id}/publish`, { method: 'POST', json: body }),
    onSuccess: invalidate,
  });
}

export function useRollback(id: string) {
  const invalidate = useInvalidate(id);
  return useMutation({
    mutationFn: (body: { version: string; andPublish?: boolean }) =>
      apiFetch<{ newVersion: string; copiedFrom: string; status: string }>(`/strategies/${id}/rollback`, {
        method: 'POST',
        json: body,
      }),
    onSuccess: invalidate,
  });
}

export function useArchive(id: string) {
  const invalidate = useInvalidate(id);
  return useMutation({
    mutationFn: () => apiFetch(`/strategies/${id}/archive`, { method: 'POST', json: {} }),
    onSuccess: invalidate,
  });
}

export function useSetNotifications() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ id, enabled, channel }: { id: string; enabled: boolean; channel: string | null }) =>
      apiFetch<{ notificationsEnabled: boolean; notificationChannel: string | null }>(
        `/strategies/${id}/notifications`,
        { method: 'PATCH', json: { enabled, channel } },
      ),
    onSuccess: invalidate,
  });
}

/** A decimal-string equity series → an SVG polyline points string (display-only scaling). */
export function sparkPoints(equity: string[], w = 80, h = 22): string {
  if (!equity || equity.length < 2) return '';
  const nums = equity.map(Number);
  const min = Math.min(...nums);
  const span = Math.max(...nums) - min || 1;
  return nums
    .map((v, i) => `${((i / (nums.length - 1)) * w).toFixed(1)},${(h - ((v - min) / span) * h).toFixed(1)}`)
    .join(' ');
}

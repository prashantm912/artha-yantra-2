// Data Ops console API (wave spec docs/superpowers/plans/2026-06-24-data-ops-console-wave.md).
// Typed fetchers + react-query hooks over the market-data admin surface (the edge-gateway proxies
// /api/v1/market/**). Status/quota hooks poll (no WS topic for the backfill); coverage is on-demand.

import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { apiFetch, listItems } from './client.ts';

// ---- wire types ----------------------------------------------------------------------------

export type BackfillState = 'NEVER_RUN' | 'RUNNING' | 'OK' | 'FAILED';

/** GET /market/admin/expired-backfill/status — live progress while RUNNING, else last-run audit. */
export interface ExpiredStatus {
  jobId: string | null;
  state: BackfillState;
  startedAt: string | null;
  lastRun: string | null;
  durationMs: number;
  expiries: number;
  contracts: number;
  legsWritten: number;
  legsSkipped: number;
  legsFailed: number;
  candleRows: number;
  currentExpiry: string | null;
  error: string | null;
  recentLogs: string[];
}

/** GET /market/admin/oi-backfill/status. */
export interface OiStatus {
  jobId: string | null;
  state: BackfillState;
  underlying: string | null;
  expiry: string | null;
  session: string | null;
  startedAt: string | null;
  lastRun: string | null;
  durationMs: number;
  optionRows: number;
  futuresRows: number;
  error: string | null;
}

/** One per-(underlying, exchange) coverage row (B2). candleRows = sum(bar_count). */
export interface CoverageRow {
  underlying: string;
  exchange: string;
  contracts: number;
  complete: number;
  partial: number;
  minExpiry: string | null;
  maxExpiry: string | null;
  candleRows: number;
}

/** One Upstox quota window (B4). */
export interface WindowStat {
  window: string; // '1s' | '1m' | '30m'
  used: number;
  max: number;
  remaining: number;
}

/** GET /market/admin/upstox-quota-status — configured=false on a mock / analytics-off stack. */
export interface QuotaStatus {
  configured: boolean;
  windows: WindowStat[];
}

/** POST /market/admin/query result grid (B5). Cells are stringified (null preserved). */
export interface QueryResult {
  columns: string[];
  rows: (string | null)[][];
  rowCount: number;
  truncated: boolean;
}

/** One registered contract for the B6 export picker. */
export interface ExportContract {
  exchange: string;
  tradingsymbol: string;
  strike: string | number | null;
  instrumentType: string;
}

/** Which expired legs to pull. BOTH = the pre-selector default (option + future legs). */
export type ContractType = 'OPTIONS' | 'FUTURES' | 'BOTH';

/** POST /market/admin/expired-backfill body (all optional; resume = empty). */
export interface BackfillRequest {
  underlyings?: string[];
  from?: string; // YYYY-MM-DD
  to?: string;
  interval?: string;
  force?: boolean;
  contractType?: ContractType;
}

/** One row of the backfill run-audit ledger (V030). params is the trigger request JSON (stringified). */
export interface BackfillJobRow {
  id: number;
  kind: string; // EXPIRED | EQUITY_DAILY
  params: string | null;
  status: string; // RUNNING | COMPLETED | FAILED
  rowsWritten: number | null;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface ExportRequest {
  exchange: string;
  symbol: string;
  from: string; // YYYY-MM-DD
  to: string;
  format: 'csv' | 'json';
}

// ---- hooks (polled where a run can be in flight) -------------------------------------------

const POLL_MS = 2_000;

export function useExpiredStatus(): UseQueryResult<ExpiredStatus> {
  return useQuery({
    queryKey: ['dataops', 'expired-status'],
    queryFn: () => apiFetch<ExpiredStatus>('/market/admin/expired-backfill/status'),
    refetchInterval: (q) => (q.state.data?.state === 'RUNNING' ? POLL_MS : false),
  });
}

export function useOiStatus(): UseQueryResult<OiStatus> {
  return useQuery({
    queryKey: ['dataops', 'oi-status'],
    queryFn: () => apiFetch<OiStatus>('/market/admin/oi-backfill/status'),
    refetchInterval: (q) => (q.state.data?.state === 'RUNNING' ? POLL_MS : false),
  });
}

export function useCoverage(): UseQueryResult<CoverageRow[]> {
  return useQuery({
    queryKey: ['dataops', 'coverage'],
    queryFn: async () =>
      listItems(await apiFetch<{ items: CoverageRow[] }>('/market/admin/coverage-summary')),
  });
}

export function useQuota(): UseQueryResult<QuotaStatus> {
  return useQuery({
    queryKey: ['dataops', 'quota'],
    queryFn: () => apiFetch<QuotaStatus>('/market/admin/upstox-quota-status'),
    refetchInterval: POLL_MS,
  });
}

/** Recent backfill runs (newest first) for the Status page history table. Polls so an in-flight run resolves. */
export function useBackfillJobs(limit = 50): UseQueryResult<BackfillJobRow[]> {
  return useQuery({
    queryKey: ['dataops', 'backfill-jobs', limit],
    queryFn: async () =>
      listItems(
        await apiFetch<{ items: BackfillJobRow[] }>(`/market/admin/backfill-jobs?limit=${limit}`),
      ),
    refetchInterval: (q) =>
      (q.state.data ?? []).some((j) => j.status === 'RUNNING') ? POLL_MS : false,
  });
}

export function useExportExpiries(underlying: string | null): UseQueryResult<string[]> {
  return useQuery({
    queryKey: ['dataops', 'export-expiries', underlying],
    enabled: !!underlying,
    queryFn: async () =>
      listItems(
        await apiFetch<{ items: string[] }>(
          `/market/admin/export/expiries?underlying=${encodeURIComponent(underlying!)}`,
        ),
      ),
  });
}

export function useExportContracts(
  underlying: string | null,
  expiry: string | null,
): UseQueryResult<ExportContract[]> {
  return useQuery({
    queryKey: ['dataops', 'export-contracts', underlying, expiry],
    enabled: !!underlying && !!expiry,
    queryFn: async () =>
      listItems(
        await apiFetch<{ items: ExportContract[] }>(
          `/market/admin/export/contracts?underlying=${encodeURIComponent(
            underlying!,
          )}&expiry=${encodeURIComponent(expiry!)}`,
        ),
      ),
  });
}

// ---- imperative fetchers ------------------------------------------------------------------

/** Trigger (or resume) the expired backfill. 202 → {jobId,status}; 409 already running; 503 not configured. */
export function triggerBackfill(body: BackfillRequest): Promise<{ jobId: string; status: string }> {
  return apiFetch('/market/admin/expired-backfill', { method: 'POST', json: body });
}

/** Run a read-only SELECT/WITH; the server enforces the allowlist + READ ONLY transaction + row cap. */
export function runQuery(sql: string, rowLimit?: number): Promise<QueryResult> {
  return apiFetch<QueryResult>('/market/admin/query', {
    method: 'POST',
    json: { sql, rowLimit },
  });
}

/** Streams the query result (or a contract export) to a downloaded file. */
async function download(path: string, body: unknown, filename: string): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  if (match) headers['X-XSRF-TOKEN'] = decodeURIComponent(match[1]);
  const res = await fetch('/api/v1' + path, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    credentials: 'include',
  });
  if (!res.ok) {
    throw new Error(`export failed (HTTP ${res.status})`);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** Download the current query result as CSV. */
export function downloadQueryCsv(sql: string): Promise<void> {
  return download('/market/admin/query/export', { sql, format: 'csv' }, 'query.csv');
}

/** Download one contract's candles (B6). */
export function downloadExport(req: ExportRequest): Promise<void> {
  return download('/market/admin/export', req, `${req.symbol}.${req.format}`);
}

export interface BulkExportRequest {
  underlying: string;
  expiry: string; // YYYY-MM-DD
  from: string;
  to: string;
  format: 'csv' | 'json';
}

/** Download a ZIP of EVERY contract of a whole (underlying, expiry) chain (B6 bulk). */
export function downloadBulkExport(req: BulkExportRequest): Promise<void> {
  const name = `${req.underlying.replace(/ /g, '_')}_${req.expiry}.zip`;
  return download('/market/admin/export/bulk', req, name);
}

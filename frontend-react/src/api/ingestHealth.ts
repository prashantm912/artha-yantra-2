// EOD ingest health board API (app-platform audit §6.3 / §9.1 — item A11). Typed fetcher + a
// react-query hook over market-data's read surface (the edge-gateway proxies /api/v1/market/**).
// Per-source last-run + per-day coverage verdicts from the marketdata.ingest_runs ledger; the
// backend delegates every per-day GREEN/YELLOW/RED verdict to the A5 coverage canary's policy.

import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** A source's health verdict for one trading day, or its overall/last-run status. */
export type IngestStatus = 'GREEN' | 'YELLOW' | 'RED';

/** The most recent ingest_runs row for a source (raw status + a computed staleness flag). */
export interface LastRun {
  status: string; // RUNNING | SUCCESS | FAILURE
  rowsWritten: number | null;
  startedAt: string;
  finishedAt: string | null;
  error: string | null;
  /** A RUNNING row that never finished and aged past the stall threshold = crashed mid-flight. */
  stale: boolean;
}

/** One trading day's verdict for a source (aligned to the board window, newest first). */
export interface DayVerdict {
  day: string; // YYYY-MM-DD
  status: IngestStatus;
}

/** One source's health: latest verdict + detail, missed-day count, the per-day strip, and last run. */
export interface SourceHealth {
  source: string;
  policy: string; // REQUIRE_SUCCESS | SCREENER | CAPTURE
  status: IngestStatus;
  detail: string;
  missingDays: number;
  days: DayVerdict[];
  lastRun: LastRun | null;
}

/** GET /market/health/ingest — the whole board over the last N settled trading days. */
export interface BoardReport {
  generatedAt: string;
  fromDay: string | null;
  toDay: string | null;
  tradingDays: number;
  sources: SourceHealth[];
}

/** Per-source ingest health over the last {@code days} settled trading days (default 10). Polls slowly. */
export function useIngestHealth(days = 10): UseQueryResult<BoardReport> {
  return useQuery({
    queryKey: ['ingest-health', days],
    queryFn: () => apiFetch<BoardReport>(`/market/health/ingest?days=${days}`),
    refetchInterval: 60_000,
  });
}

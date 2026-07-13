import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** One per-symbol completeness verdict within a nightly data-quality report. */
export interface CompletenessRow {
  scope: string;
  symbol: string;
  expected: number;
  present: number;
  coveragePct: number | null;
  ok: boolean;
  detail: string | null;
  computedAt: string;
}

/** GET /market/health/completeness — the report for one settled trading day. */
export interface CompletenessReport {
  date: string | null;
  items: CompletenessRow[];
}

/** Latest or date-selected completeness report. Polls slowly like the ingest-health board. */
export function useDataQuality(date?: string | null): UseQueryResult<CompletenessReport> {
  return useQuery({
    queryKey: ['data-quality', date ?? 'latest'],
    queryFn: () =>
      apiFetch<CompletenessReport>(
        `/market/health/completeness${date ? `?date=${date}` : ''}`,
      ),
    refetchInterval: 60_000,
  });
}

// Screener time-machine data layer (audit §6.7 Phase-3): recent screen dates, the day-over-day
// entered/left passer diff, and the funnel stage-attrition — shared by both screeners over the
// symmetric /market/screener/{family}/{dates,diff,attrition} endpoints. Decimals ride as JSON strings.

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** The two screener families, matching the backend path segments. */
export type ScreenerFamily = 'minervini' | 'manas-arora';

export interface ScreenDateItem {
  date: string;
  coverage: number;
  passers: number;
}

export interface DiffRow {
  symbol: string;
  rsRank?: string | null;
}

export interface ScreenDiff {
  screenDate: string | null;
  priorDate: string | null;
  enteredCount: number;
  leftCount: number;
  entered: DiffRow[];
  left: DiffRow[];
}

export interface GateCount {
  gate: number;
  passed: number;
}

export interface FunnelAttrition {
  screenDate: string | null;
  scanned: number;
  gates: GateCount[];
  passesAll: number;
  buyable: number;
  onDeck: number;
  watch: number;
}

/** The recent persisted screen dates (newest first) — the date-picker source. */
export function useScreenerDates(family: ScreenerFamily, enabled = true) {
  return useQuery({
    queryKey: ['screener-dates', family],
    queryFn: () =>
      apiFetch<{ items: ScreenDateItem[] }>(`/market/screener/${family}/dates`).then((r) => r.items),
    enabled,
  });
}

/** The day-over-day passer diff between {@code prior} and {@code asOf} (both optional; server defaults). */
export function useScreenerDiff(
  family: ScreenerFamily,
  asOf?: string | null,
  prior?: string | null,
  enabled = true,
) {
  const params = new URLSearchParams();
  if (asOf) params.set('asOf', asOf);
  if (prior) params.set('prior', prior);
  const suffix = params.toString() ? `?${params.toString()}` : '';
  return useQuery({
    queryKey: ['screener-diff', family, asOf ?? 'latest', prior ?? 'auto'],
    queryFn: () => apiFetch<ScreenDiff>(`/market/screener/${family}/diff${suffix}`),
    enabled,
  });
}

/** The funnel stage-attrition (scanned → per-gate → buyable) for a screen date. */
export function useScreenerAttrition(family: ScreenerFamily, asOf?: string | null, enabled = true) {
  const suffix = asOf ? `?asOf=${asOf}` : '';
  return useQuery({
    queryKey: ['screener-attrition', family, asOf ?? 'latest'],
    queryFn: () => apiFetch<FunnelAttrition>(`/market/screener/${family}/attrition${suffix}`),
    enabled,
  });
}

/** Builds the CSV-export URL for a family (consumed by {@code downloadFile}). */
export function screenerExportPath(
  family: ScreenerFamily,
  asOf: string | null,
  passesAllOnly: boolean,
): string {
  const params = new URLSearchParams({ passesAllOnly: String(passesAllOnly) });
  if (asOf) params.set('asOf', asOf);
  return `/market/screener/${family}/export?${params.toString()}`;
}

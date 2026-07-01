// Signal-rejection diagnostics data layer: every scalper chart-entry the live §12.3 confluence gate
// BLOCKED, with the first failing rail + margin, the dot-by-dot confluence, and the raw OI/macro/chart
// context. Read-only (INSERT-only from the live engine). Powers the "Signal Rejections" analysis page.

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** A numeric wire value: top-level NUMERIC columns arrive as decimal strings, JSONB fields as numbers. */
export type Num = number | string | null;

/** One rail evaluated up to (and including) the block: what it tested, pass/fail, and by what margin. */
export interface RailCheck {
  rail: string;
  pass: boolean;
  operand: Num;
  threshold: Num;
  margin: Num;
  reason: string | null;
}

/** One Connect-the-Dots confluence dot (green supports the side, red opposes it). */
export interface RejectionDot {
  dot: string;
  weight: number;
  supports: boolean;
  reason: string | null;
}

/** The full confluence score (present only when the gate reached the composite stage). */
export interface RejectionConfluence {
  aggregate: Num;
  threshold: Num;
  bullish: boolean;
  bearish: boolean;
  vwapAligned: boolean;
  biasAligned: boolean;
  standAside: boolean;
  dots: RejectionDot[];
}

/** The raw per-bar OI/macro/chart inputs (present only when the block happened after the chain fetch). */
export interface RejectionContext {
  underlying?: string;
  signalIndex?: string;
  chart?: Record<string, Num>;
  oi?: Record<string, Num | boolean | string>;
  macro?: Record<string, Num | boolean>;
}

/** The complete "why blocked" payload (the JSONB column). */
export interface RejectionDiagnostic {
  blockingRail: string;
  side: string | null;
  operand: Num;
  threshold: Num;
  margin: Num;
  reason: string | null;
  compositeScore: Num;
  compositeThreshold: Num;
  checks: RailCheck[];
  confluence?: RejectionConfluence | null;
  context?: RejectionContext | null;
}

/** One rejection row. */
export interface SignalRejectionDto {
  id: number;
  strategyVersionId?: string | null;
  strategySlug: string;
  exchange: string;
  tradingsymbol: string;
  interval: string;
  side: string | null;
  blockingRail: string;
  blockingOperand: Num;
  blockingThreshold: Num;
  blockingMargin: Num;
  blockingReason: string | null;
  compositeScore: Num;
  compositeThreshold: Num;
  diagnostic: RejectionDiagnostic;
  barTime: string;
  generatedAt: string;
}

interface RejectionPage {
  items: SignalRejectionDto[];
}

/** One (rail, count) aggregate. */
export interface RailCount {
  rail: string;
  count: number;
}

const KEY = 'signal-rejections';

/**
 * REST history of blocked scalper entries, newest first. `rail` filters to one blocking condition;
 * `from`/`to` are ISO datetimes bounding `generated_at` (Live=today vs a picked day). All filters join
 * the query key so each combination caches separately.
 */
export function useSignalRejections(
  strategyVersionId: string | null = null,
  rail: string | null = null,
  from: string | null = null,
  to: string | null = null,
  limit = 200,
) {
  return useQuery({
    queryKey: [KEY, strategyVersionId, rail, from, to, limit],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(limit), offset: '0' });
      if (strategyVersionId) params.set('strategyVersionId', strategyVersionId);
      if (rail) params.set('rail', rail);
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      return apiFetch<RejectionPage>(`/signal-rejections?${params.toString()}`);
    },
  });
}

/** The per-rail block rollup (which condition blocks most) over the same optional window. */
export function useRejectionRailCounts(
  strategyVersionId: string | null = null,
  from: string | null = null,
  to: string | null = null,
) {
  return useQuery({
    queryKey: [KEY, 'rail-counts', strategyVersionId, from, to],
    queryFn: () => {
      const params = new URLSearchParams();
      if (strategyVersionId) params.set('strategyVersionId', strategyVersionId);
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      const qs = params.toString();
      return apiFetch<{ items: RailCount[] }>(`/signal-rejections/rail-counts${qs ? `?${qs}` : ''}`);
    },
  });
}

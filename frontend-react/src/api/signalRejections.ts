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
  offset = 0,
) {
  return useQuery({
    queryKey: [KEY, strategyVersionId, rail, from, to, limit, offset],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
      if (strategyVersionId) params.set('strategyVersionId', strategyVersionId);
      if (rail) params.set('rail', rail);
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      return apiFetch<RejectionPage>(`/signal-rejections?${params.toString()}`);
    },
  });
}

/** One shadow-book variant rollup row (champion vs challenger configs on identical data). */
export interface ShadowVariantSummary {
  variant: string;
  open: number;
  closed: number;
  wins: number;
  losses: number;
  pnlPoints: Num;
  /** 1-lot INR net of the statutory cost model (F8); null before the cost columns existed. */
  pnlNet: Num;
  /** CLOSED rows with no pnl_net (STALE / pre-F8 history) — in `closed` but NOT in `pnlNet`. */
  unpriced: number;
}

/** The per-variant shadow-book league table over an optional opened-at window. */
export function useShadowSummary(from: string | null = null, to: string | null = null) {
  return useQuery({
    queryKey: [KEY, 'shadow-summary', from, to],
    queryFn: () => {
      const params = new URLSearchParams();
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      const qs = params.toString();
      return apiFetch<{ items: ShadowVariantSummary[] }>(
        `/signal-rejections/shadow-summary${qs ? `?${qs}` : ''}`,
      );
    },
  });
}

/** One gate-input dot's current verdict (the DotHealthCanary.DotState record). */
export interface DotState {
  /** The Connect-the-Dots dot whose input this probes (e.g. `breadth`, `iv_rank`, `vix`). */
  dot: string;
  /** True when the dot's input was present in at least one of today's newest rejections. */
  alive: boolean;
  /** True when the dot is on the `required-dots` list — expected alive today (a dead one pages). */
  required: boolean;
  /** Human liveness detail ("input live in the last N rejections" / "input dead across N" / "no rejections yet today"). */
  detail: string;
}

/**
 * Per-DOT gate-input liveness over TODAY's newest CONTEXT-BEARING rejections (roadmap F4 v2; T17).
 * `asOf` is the evaluation time (IST offset), `session` whether the market is open, `rowsScanned` the
 * raw page depth, `rowsInspected` the context-bearing rows the probes actually read (0 with
 * rowsScanned > 0 = only early-rail blocks so far — UNINFORMATIVE, not an all-dead verdict).
 */
export interface DotHealth {
  asOf: string;
  session: boolean;
  rowsScanned: number;
  rowsInspected: number;
  dots: DotState[];
}

/**
 * Per-DOT gate-input liveness — which Connect-the-Dots inputs are live vs dead, and which are REQUIRED
 * alive. A dead input silently re-caps the composite (the 0.765-ceiling class the forensics found months
 * late), so this is the owner's pre-market dead-dot check. It always inspects TODAY's rejections,
 * independent of the page's date filter; polls slowly during the session.
 */
export function useDotHealth() {
  return useQuery({
    queryKey: [KEY, 'dot-health'],
    queryFn: () => apiFetch<DotHealth>('/signal-rejections/dot-health'),
    refetchInterval: 60_000,
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

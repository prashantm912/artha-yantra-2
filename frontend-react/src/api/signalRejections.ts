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

/**
 * Per-row gate-input health, computed when the row was recorded (F5 U3, V054). A flag means the input
 * was ABSENT when the gate scored that bar — never that its value was unremarkable.
 */
export interface RejectionDataHealth {
  degraded: boolean;
  /** False when the block landed before the chain fetch — uninformative, NOT unhealthy. */
  contextBearing: boolean;
  /**
   * True when this row's OI root is on ITS OWN monthly index expiry (NSE last Tuesday / BSE last
   * Thursday), where MarketOiClient skips the OI block by design (S24). The OI flag is then withheld
   * — an inert OI block on that root's expiry is not a defect. Keyed per root, so the other root's
   * rows the same session are still judged normally.
   */
  oiSuppressed: boolean;
  /** The absent inputs, e.g. `iv-rank-absent`, `breadth-absent`, `oi-inert`. */
  flags: string[];
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
  /** null on rows recorded before V054 — "never computed", NOT "judged healthy". */
  dataHealth?: RejectionDataHealth | null;
  degraded: boolean;
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
 * `from`/`to` are ISO datetimes bounding `generated_at` (Live=today vs a picked day); `degraded`
 * narrows to rows whose gate inputs were (or were not) absent. All filters join the query key so each
 * combination caches separately.
 */
export function useSignalRejections(
  strategyVersionId: string | null = null,
  rail: string | null = null,
  from: string | null = null,
  to: string | null = null,
  limit = 200,
  offset = 0,
  degraded: boolean | null = null,
) {
  return useQuery({
    queryKey: [KEY, strategyVersionId, rail, from, to, limit, offset, degraded],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
      if (strategyVersionId) params.set('strategyVersionId', strategyVersionId);
      if (rail) params.set('rail', rail);
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      if (degraded !== null) params.set('degraded', String(degraded));
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
  /**
   * True when the input is present but carries ONE distinct value across today's bars (G12). A frozen
   * dot re-caps the composite as silently as a dead one while every alive/dead probe reports it alive.
   * Reported, never paged — `iv_abs_band` freezes legitimately (its operand is an EOD daily scalar),
   * so `detail` says whether the freeze is by design.
   */
  frozen: boolean;
  /**
   * True for the FOURTH input state (G16): live AND moving (so neither the liveness nor the frozen
   * probe can see it) yet effectively non-discriminating — supports one-sided within a small
   * tolerance across the session's distinct (bar, side) verdicts, with the operand's extremum
   * hugging the dot's threshold. breadth on 2026-07-30: 0/814 supports, 10 distinct values, session
   * max EXACTLY 32 against its `> 32` rule. Judged SESSION-WIDE (not over the bounded window the
   * `alive`/`frozen` probes read). Telemetry only, never pages; `detail` carries the numbers.
   */
  neverCrossing: boolean;
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

/**
 * The per-rail block rollup (which condition blocks most) over the same optional window.
 * `strategySlug` narrows it to ONE strategy — what the funnel needs to fan the confluence-blocked
 * bucket out by rail, keyed on the same stable identity the V053 denominator uses.
 */
export function useRejectionRailCounts(
  strategyVersionId: string | null = null,
  from: string | null = null,
  to: string | null = null,
  strategySlug: string | null = null,
) {
  return useQuery({
    queryKey: [KEY, 'rail-counts', strategyVersionId, from, to, strategySlug],
    queryFn: () => {
      const params = new URLSearchParams();
      if (strategyVersionId) params.set('strategyVersionId', strategyVersionId);
      if (strategySlug) params.set('strategySlug', strategySlug);
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      const qs = params.toString();
      return apiFetch<{ items: RailCount[] }>(`/signal-rejections/rail-counts${qs ? `?${qs}` : ''}`);
    },
  });
}

/** One (strategy, outcome) evaluation total for a session date, already summed across boots. */
export interface EvalOutcomeCount {
  strategySlug: string;
  /** The stable `SignalEngine.Outcome` wire tag, e.g. `chart-gate-failed` / `fired`. */
  outcome: string;
  evalCount: number;
}

/**
 * The V053 per-strategy evaluation ladder for one IST session date (F5 unit U2).
 *
 * `boots` is how many process boots contributed — `> 1` means the day spans a restart and the totals
 * are a SUM across them. An EMPTY `items` means the rollup wrote nothing for that date (a down stack,
 * a pre-V053 date, or one past retention); the table never writes zero rows, so absence is genuinely
 * unknown and must never be rendered as "nothing evaluated".
 */
export interface EvalFunnel {
  sessionDate: string;
  boots: number;
  items: EvalOutcomeCount[];
}

/**
 * The real per-strategy evaluation denominator for one IST SESSION date (the date of the evaluated
 * bars, not a UTC calendar date). Every rate on the funnel is computed against this rather than one
 * inferred from the 3m grid — the thing U2 was built to fix.
 */
export function useEvalFunnel(date: string, enabled = true) {
  return useQuery({
    queryKey: [KEY, 'eval-funnel', date],
    enabled,
    queryFn: () =>
      apiFetch<EvalFunnel>(`/signal-rejections/eval-funnel?date=${encodeURIComponent(date)}`),
  });
}

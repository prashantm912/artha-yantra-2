// Insights decision-support data layer (INT design §8–§9, increment I1 — shadow mode: read + triage
// only, NO push/WS). Ports the `/api/v1/insights/**` typed surface: the feed list, the one-call Focus
// (signal + attention queues), the badge summary, and the display-side triage writes (ack / dismiss /
// feedback). The list/get/focus responses are self-contained for audit (§9.4) — evidence + the
// priority explain contract ride the row — so the explain drawer renders straight from a feed/Focus
// row with no extra fetch. `evidence` / `priorityDetail` arrive as parsed JSON (Jackson JsonNode on
// the wire) shaped per Evidence.java / PriorityDetail.java.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** Insight severity ladder (§2.1). */
export const INSIGHT_SEVERITIES = ['INFO', 'NOTICE', 'WARN', 'CRITICAL'] as const;
export type InsightSeverity = (typeof INSIGHT_SEVERITIES)[number];

/** Insight lifecycle status (§2.1). */
export const INSIGHT_STATUSES = ['OPEN', 'ACKED', 'ACTED', 'DISMISSED', 'EXPIRED'] as const;
export type InsightStatus = (typeof INSIGHT_STATUSES)[number];

/** Per-insight data-trust state (§7.2). */
export const INSIGHT_TRUST = ['OK', 'DEGRADED', 'BLOCKED'] as const;
export type InsightTrust = (typeof INSIGHT_TRUST)[number];

/** Priority band (§3.2). null = unscored (BLOCKED — never rank on bad data). */
export type InsightBand = 'A' | 'B' | 'C' | 'D';

/**
 * The initial generator catalog (§2.2) — used ONLY to seed the feed's type-filter dropdown. `type` is
 * a free string on the wire, so an unknown type still lists and renders; this is a convenience set, not
 * an enforced enum.
 */
export const INSIGHT_TYPES = [
  'SIGNAL_PRIORITY',
  'REJECTION_NEARMISS',
  'REJECTION_RAIL_TREND',
  'CONTEXT_SHIFT',
  'MARKET_STRUCTURE',
  'RISK_HEAT',
  'RISK_STALE_TICK',
  'DATA_TRUST',
  'STRATEGY_EVIDENCE',
  'SELL_DECISION',
  'HYGIENE',
  'EXPIRY_EVENT',
] as const;

/** The navigable source behind an evidence value (§3.4). */
export interface EvidenceSource {
  endpoint?: string;
  params?: Record<string, unknown>;
  asOf?: string;
}

/** One evidence pointer (§2.1) — a labelled value plus, optionally, its source + a domain ref. */
export interface InsightEvidence {
  label: string;
  value: string;
  source?: EvidenceSource;
  ref?: Record<string, unknown>;
}

/** One weighted priority component (§3.4): weight × c = points, with the evidence behind its c. */
export interface PriorityComponent {
  key: string;
  weight: number;
  c: number;
  points: number;
  evidence?: InsightEvidence[];
}

/** The priority explain contract (§3.4), mirroring the frozen ScoreBreakdown convention. */
export interface PriorityDetail {
  score: number;
  band: InsightBand;
  trustCap: number;
  components: PriorityComponent[];
}

/** One insight row = the wire DTO for GET `/insights` + `/{id}` (Insight.java). */
export interface Insight {
  id: string;
  generatedAt: string;
  type: string;
  severity: InsightSeverity;
  scope: string;
  title: string;
  explanation: string;
  /** MANDATORY + non-empty on write; typed loosely for a null-safe render. */
  evidence?: InsightEvidence[] | null;
  priority?: number | null;
  priorityDetail?: PriorityDetail | null;
  dataTrust: InsightTrust;
  trustReasons?: string[] | null;
  dedupeKey?: string;
  cooldownUntil?: string | null;
  suppressed: boolean;
  status: InsightStatus;
  expiresAt?: string | null;
  engineVersion?: string;
  configHash?: string;
}

/** The `{items}` feed envelope (§9.1). */
export interface InsightListResponse {
  items: Insight[];
  limit: number;
  offset: number;
}

/** The one-call Focus surface: ranked signal queue + attention queue (§3.1 / §8.1). */
export interface FocusResponse {
  signalQueue: Insight[];
  attentionQueue: Insight[];
  suppressed: number;
}

/** A (key, count) badge tally. */
export interface InsightCount {
  key: string;
  count: number;
}

/** Focus-header + feed badge counts over OPEN insights (§9.1). */
export interface InsightSummaryResponse {
  bySeverity: InsightCount[];
  byStatus: InsightCount[];
  suppressed: number;
}

/** The result of a triage write (ack / dismiss / feedback). */
export interface TriageResponse {
  id: string;
  status: string;
}

/** Owner feedback verdict (§2.4). */
export type FeedbackVerdict = 'USEFUL' | 'NOT_USEFUL';

/** Feed query filters — all optional; mirror the server params (`day` is an IST YYYY-MM-DD). */
export interface InsightFilters {
  type?: string | null;
  severity?: string | null;
  status?: string | null;
  scope?: string | null;
  day?: string | null;
  includeSuppressed?: boolean;
  limit?: number;
  offset?: number;
}

const INSIGHTS_KEY = 'insights';

/** Today's calendar date (YYYY-MM-DD) in IST, robust to the browser timezone (feed day picker). */
export function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
}

/**
 * The priority band: from the explain contract when present (§3.4), else derived from the numeric
 * priority via the §3.2 bands (A ≥ 80, B 60–79, C 40–59, D < 40). `null` = unscored (a BLOCKED insight
 * carries no priority — never rank on bad data).
 */
export function insightBand(insight: Insight): InsightBand | null {
  const b = insight.priorityDetail?.band;
  if (b === 'A' || b === 'B' || b === 'C' || b === 'D') return b;
  const p = insight.priority;
  if (p == null) return null;
  if (p >= 80) return 'A';
  if (p >= 60) return 'B';
  if (p >= 40) return 'C';
  return 'D';
}

/** Compact relative age for a feed/Focus row: "12s" · "3m" · "2h" · "4d". */
export function formatAge(iso: string, now: number = Date.now()): string {
  const ms = now - new Date(iso).getTime();
  if (!Number.isFinite(ms) || ms < 0) return 'now';
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  return `${Math.floor(h / 24)}d`;
}

/** Paged/filtered feed, newest first (`{items}` envelope). */
export function useInsights(filters: InsightFilters) {
  return useQuery({
    queryKey: [INSIGHTS_KEY, 'list', filters],
    queryFn: () => {
      const p = new URLSearchParams();
      p.set('limit', String(filters.limit ?? 100));
      p.set('offset', String(filters.offset ?? 0));
      if (filters.type) p.set('type', filters.type);
      if (filters.severity) p.set('severity', filters.severity);
      if (filters.status) p.set('status', filters.status);
      if (filters.scope) p.set('scope', filters.scope);
      if (filters.day) p.set('day', filters.day);
      if (filters.includeSuppressed) p.set('includeSuppressed', 'true');
      return apiFetch<InsightListResponse>(`/insights?${p.toString()}`);
    },
  });
}

/** The ranked signal queue + attention queue in one call (Focus panel, §8.1). */
export function useFocus(limit = 20) {
  return useQuery({
    queryKey: [INSIGHTS_KEY, 'focus', limit],
    queryFn: () => apiFetch<FocusResponse>(`/insights/focus?limit=${limit}`),
  });
}

/** OPEN badge counts by severity + status (Focus header). */
export function useInsightSummary() {
  return useQuery({
    queryKey: [INSIGHTS_KEY, 'summary'],
    queryFn: () => apiFetch<InsightSummaryResponse>('/insights/summary'),
  });
}

/** Ack/dismiss share one shape — a status transition + an insight_actions audit row on the server. */
function useTriage(kind: 'ack' | 'dismiss') {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<TriageResponse>(`/insights/${id}/${kind}`, { method: 'POST', json: {} }),
    // The feed/Focus/summary all move together on a triage — refetch the whole family.
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [INSIGHTS_KEY] });
    },
  });
}

/** Acknowledge an insight (status → ACKED). */
export function useAckInsight() {
  return useTriage('ack');
}

/** Dismiss an insight (status → DISMISSED). */
export function useDismissInsight() {
  return useTriage('dismiss');
}

/** Record owner feedback (USEFUL / NOT_USEFUL) on an insight (§2.4 / §10.2). */
export function useInsightFeedback() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, verdict, note }: { id: string; verdict: FeedbackVerdict; note?: string }) =>
      apiFetch<TriageResponse>(`/insights/${id}/feedback`, { method: 'POST', json: { verdict, note } }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [INSIGHTS_KEY] });
    },
  });
}

// ─── I3: compare · dossier · act ──────────────────────────────────────────────────────────────────
// The I3 wave adds the decide-BETWEEN (compare), the strategy-evidence dossier, and the one-click
// PROPOSE executor (#778 backend). Every mutation still flows through an EXISTING governed endpoint —
// /act returns a PREFILL the browser posts to /paper/orders or /journal (never a new mutation path),
// or a PROPOSED instruction the browser completes; ack/dismiss/mute are in-module idempotent writes.

/** One priority component's contribution in a compare column (§4.1 / §3.4 render). */
export interface ComponentPoint {
  key: string;
  points?: number | null;
  c?: number | null;
}

/** One signal's compare column (§4.1). `scored`: SCORED | BLOCKED | NO_INSIGHT. */
export interface CompareColumn {
  signalId: number;
  tradingsymbol?: string;
  side?: string;
  family?: string;
  book?: string;
  priority?: number | null;
  band?: string | null;
  components?: ComponentPoint[];
  optionLegCost?: number | null;
  /** Honest "unpriced" ride-through — the backend never fabricates a margin number (§4.1). */
  marginEstimate?: string;
  riskReward?: number | null;
  entryPrice?: number | null;
  stopLoss?: number | null;
  target?: number | null;
  dataTrust?: string;
  trustReasons?: string[] | null;
  scored?: string;
}

/** The compare matrix (§4.1 / §8.5) — signals as columns, priority components as rows. */
export interface CompareResult {
  session?: string;
  booksDiffer?: boolean;
  /** The component whose points spread most across the set — "they differ mainly on X". */
  differsMost?: string | null;
  columns?: CompareColumn[];
  notes?: string[];
}

/** One scored graduation criterion (name / human bound / actual / pass) — mirrors the board's. */
export interface Criterion {
  name?: string;
  required?: string;
  actual?: string;
  pass?: boolean;
}

/** One threshold-crossing timeline entry (the STRATEGY_EVIDENCE history the board lacks, §5.2). */
export interface CrossingEntry {
  at?: string;
  severity?: string;
  title?: string;
}

/** One blocking-rail count in the dossier rejection profile. */
export interface RailCount {
  rail?: string;
  count?: number;
}

/** One open sell-decision row in the dossier (§5.3). */
export interface OpenSell {
  sellDecisionId: number;
  runDate?: string;
  symbol?: string;
  verdict?: string;
  unrealizedPct?: number | null;
  acknowledged?: boolean;
}

/** The server-assembled qualification dossier (§5.1). */
export interface Dossier {
  strategyId: string;
  slug?: string | null;
  name?: string | null;
  enabled?: boolean;
  stage?: string;
  criteria?: Criterion[];
  graduatedAt?: string | null;
  crossingTimeline?: CrossingEntry[];
  rejectionProfile?: RailCount[];
  openSellDecisions?: OpenSell[];
  asOf?: string;
  notes?: string[];
}

/** A ticket prefill (§11.4) — the leg/side/qty/SL/TP the owner reviews before the existing endpoint post. */
export interface TicketPrefill {
  signalId: number;
  exchange?: string;
  tradingsymbol?: string;
  side?: string;
  qty?: number | null;
  stopLoss?: number | null;
  target?: number | null;
}

/** A journal-draft prefill (§8.6 draft-accept flow) — the owner's browser posts it to /journal. */
export interface JournalDraft {
  signalId?: number | null;
  paperPositionId?: number | null;
  tags?: string[] | null;
  note?: string;
}

/** The one-click PROPOSE result (§9.1). `status`: DONE (in-module) | PREFILL (browser posts to target) | PROPOSED. */
export interface ActResponse {
  insightId: string;
  action: string;
  status: 'DONE' | 'PREFILL' | 'PROPOSED';
  targetMethod?: string;
  targetEndpoint?: string;
  ticket?: TicketPrefill;
  journal?: JournalDraft;
  instrument?: string;
  sellDecisionId?: number;
  note?: string;
}

/** The PROPOSE action enum the /act executor accepts (§2.4). */
export type ProposeAction =
  | 'OPEN_TICKET'
  | 'TAKE_SIGNAL'
  | 'JOURNAL_DRAFT_ACCEPT'
  | 'MUTE_TYPE'
  | 'ACK_SELL_DECISION'
  | 'ADD_WATCHLIST';

/** One rendered PROPOSE button spec: the action, its label, and whether it is an order-placing action. */
export interface ActionSpec {
  action: ProposeAction;
  label: string;
  /** Order-placing actions are trust-gated OFF on DEGRADED (§1.2 / §7.4). */
  order: boolean;
}

/** The signal id behind a `signal:<id>` scope, else null. */
export function signalScopeId(insight: Insight): number | null {
  const scope = insight.scope;
  if (!scope || !scope.startsWith('signal:')) return null;
  const n = Number(scope.slice('signal:'.length));
  return Number.isFinite(n) ? n : null;
}

/**
 * The PROPOSE actions that apply to an insight, by scope + type (mirrors the backend /act switch). A
 * signal-scoped insight can be taken to a ticket or drafted to the journal; a SELL_DECISION insight can
 * be acknowledged; every insight's type can be muted. ack/dismiss live on the drawer's triage row, so
 * they are intentionally NOT repeated here.
 */
export function insightActions(insight: Insight): ActionSpec[] {
  const specs: ActionSpec[] = [];
  if (signalScopeId(insight) != null) {
    specs.push({ action: 'OPEN_TICKET', label: 'Take → ticket', order: true });
    specs.push({ action: 'JOURNAL_DRAFT_ACCEPT', label: 'Draft journal', order: false });
  }
  if (insight.type === 'SELL_DECISION') {
    specs.push({ action: 'ACK_SELL_DECISION', label: 'Acknowledge sell', order: false });
  }
  specs.push({ action: 'MUTE_TYPE', label: 'Mute type', order: false });
  return specs;
}

/**
 * The trust-gate disabled reason for an action, or null when enabled — the client mirror of the §7.4
 * hard line the backend enforces (BLOCKED refuses ALL actions; DEGRADED refuses the order prefills), so
 * the button renders disabled + explained BEFORE the click rather than only surfacing the 422.
 */
export function actionGate(insight: Insight, spec: ActionSpec): string | null {
  if (insight.dataTrust === 'BLOCKED') {
    return 'Data trust is BLOCKED — advice cannot outrun its data (§7.4).';
  }
  if (spec.order && insight.dataTrust === 'DEGRADED') {
    return 'Data trust is DEGRADED — the order prefill is gated off (§1.2).';
  }
  return null;
}

/**
 * Candidate-trade compare (§4.1): 2–6 same-session signal ids → the priority matrix + cost / R:R /
 * trust. Disabled until 2–6 ids are present (the backend 422s otherwise). Ids are sent comma-joined
 * (Spring binds a single `signalIds=a,b,c` param to the List).
 */
export function useCompare(signalIds: number[]) {
  const ids = signalIds.filter((n) => Number.isFinite(n));
  return useQuery({
    queryKey: [INSIGHTS_KEY, 'compare', ids],
    enabled: ids.length >= 2 && ids.length <= 6,
    queryFn: () => apiFetch<CompareResult>(`/insights/compare?signalIds=${ids.join(',')}`),
  });
}

/** The qualification dossier for one strategy (§5.1). Disabled when the id is absent. */
export function useDossier(strategyId: string | null) {
  return useQuery({
    queryKey: [INSIGHTS_KEY, 'dossier', strategyId],
    enabled: strategyId != null && strategyId !== '',
    queryFn: () => apiFetch<Dossier>(`/insights/strategy-dossier/${strategyId}`),
  });
}

/**
 * The one-click PROPOSE executor (§1.2 / §9.1). Writes an `insight_actions` row FIRST, then returns a
 * DONE / PREFILL / PROPOSED result the caller routes: DONE is complete; PREFILL is posted through the
 * existing /paper/orders or /journal UI; PROPOSED is completed against the existing target endpoint. A
 * BLOCKED insight 422s all actions, a DEGRADED insight 422s the order prefills — surfaced via ApiError.
 */
export function useAct() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action, qty }: { id: string; action: ProposeAction; qty?: number }) =>
      apiFetch<ActResponse>(`/insights/${id}/act`, {
        method: 'POST',
        json: qty != null ? { action, qty } : { action },
        silenceToast: true, // the 422 trust-gate reason renders inline on the action row, not a toast
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [INSIGHTS_KEY] });
    },
  });
}

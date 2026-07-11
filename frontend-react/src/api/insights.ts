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

// Evolution-engine data layer (design 2026-07-10 §6–§12, E6 item 17). Ports the read-only
// `/api/v1/evolution/**` typed surface (optimizer-service; #720–#767): campaigns, candidates (with
// the §6.3 scorecard JSONB embedded), per-sweep parameter-effect insights, per-version
// reconciliations, and the owner proposals inbox with the two owner-clicked writes (approve /
// reject). List endpoints use the `{items:[…]}` envelope; the candidate scorecard rides its list row
// (self-contained, so the candidate card renders with no extra fetch — the insights.ts pattern).
//
// Nothing here arms live behaviour: approve/reject only transition a proposal row + write an audit
// row (design §8.2 — the PROMOTE/PUBLISH execute action stays a separate owner step, not exposed).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

// --- enums (mirrored from the V011/V012 CHECK constraints — the wire is a free string, so an
//     unknown value still renders; these drive filter dropdowns + badge tone maps) ---------------

/** evo_campaigns.evidence_policy (design §1.2 routing). */
export const EVIDENCE_POLICIES = ['SIM_FIRST', 'LIVE_FIRST', 'SIM_BLOCKED'] as const;
export type EvidencePolicy = (typeof EVIDENCE_POLICIES)[number];

/** evo_campaigns.status. */
export const CAMPAIGN_STATUSES = ['ACTIVE', 'PAUSED', 'CLOSED'] as const;
export type CampaignStatus = (typeof CAMPAIGN_STATUSES)[number];

/** evo_candidates.state — the §8.1 candidate state machine. */
export const CANDIDATE_STATES = [
  'PROPOSED',
  'EVALUATING',
  'SCORED',
  'SURVIVOR',
  'RETIRED',
  'PAPER',
  'TAKE_ELIGIBLE',
  'PROMOTED',
  'ROLLED_BACK',
] as const;
export type CandidateState = (typeof CANDIDATE_STATES)[number];

/** evo_proposals.kind. */
export const PROPOSAL_KINDS = ['PUBLISH_PAPER', 'PROMOTE', 'RETIRE', 'ROLLBACK', 'REVIEW_GATE'] as const;
export type ProposalKind = (typeof PROPOSAL_KINDS)[number];

/** evo_proposals.status. */
export const PROPOSAL_STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'] as const;
export type ProposalStatus = (typeof PROPOSAL_STATUSES)[number];

/** reconciliations.verdict — the §7.2 divergence classification. */
export type ReconVerdict = 'ALIGNED' | 'PENALIZED' | 'DIVERGENT' | 'INSUFFICIENT';

// --- read models (mirror the Pydantic response models 1:1) --------------------------------------

/** One propose→evaluate→select iteration (evo_generations / GenerationModel). */
export interface Generation {
  id: string;
  campaignId: string;
  n: number;
  proposal?: Record<string, unknown> | null;
  searchSpaceHash?: string | null;
  engineSha?: string | null;
  dataEpoch?: Record<string, unknown> | null;
  stressTouches: number;
  status?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

/** A long-lived evolution program for one base strategy (evo_campaigns / CampaignModel). */
export interface Campaign {
  id: string;
  strategyId?: string | null;
  family?: string | null;
  evidencePolicy: string;
  objectiveSpec?: Record<string, unknown> | null;
  searchSpace?: Record<string, unknown> | null;
  budget?: Record<string, unknown> | null;
  status: string;
  championVersionId?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** A campaign plus its generations (the `/campaigns/{id}` detail shape). */
export interface CampaignDetail extends Campaign {
  generations: Generation[];
}

/** One §6.1 hard gate result on a scorecard. */
export interface GateResult {
  id: string;
  status: string; // PASS | FAIL | SKIPPED | UNKNOWN | NOT_IMPLEMENTED
  value?: unknown;
  note?: string | null;
}

/** One §6.2 RobustScore component: its within-cohort z + the raw constituents behind it. */
export interface ComponentScore {
  id: string;
  z: number;
  raw?: Record<string, unknown>;
  caveat?: string | null;
}

/** §6.2 subtractive penalties. */
export interface Penalties {
  dof: number;
  caveats: number;
}

/** The evidence chain behind a scorecard (§6.3). */
export interface ScorecardEvidence {
  simRuns?: string[];
  holdoutRun?: string | null;
  liveWindow?: Record<string, unknown> | null;
}

/** The §6.3 scorecard embedded on each candidate row (evo_candidates.scorecard). */
export interface Scorecard {
  trialNumber?: number | null;
  runId?: string | null;
  params?: Record<string, unknown> | null;
  policy?: string;
  direction?: string;
  robustScore?: number;
  rank?: number | null;
  rankable?: boolean;
  weights?: Record<string, number>;
  gates?: GateResult[];
  components?: ComponentScore[];
  penalties?: Penalties;
  flags?: string[];
  caveats?: string[];
  evidence?: ScorecardEvidence;
  /** §7.2 live-gap panel — verdict / gapZ / window / diagnosis; null on a sim-only candidate. */
  liveGap?: Record<string, unknown> | null;
  comparator?: { championVersionId?: string; delta?: number } | null;
  /** §8.2 select-step rationale, present once a generation's SELECT pass has run. */
  selection?: Record<string, unknown> | null;
}

/** One concrete version under evaluation (evo_candidates / CandidateModel). */
export interface Candidate {
  id: string;
  generationId: string;
  versionId?: string | null;
  parentCandidateId?: string | null;
  mutationKind?: string | null;
  params?: Record<string, unknown> | null;
  structureDiff?: Record<string, unknown> | null;
  sweepJobId?: string | null;
  holdoutRunId?: string | null;
  scorecard?: Scorecard | null;
  state?: string | null;
  updatedAt?: string | null;
}

/** One owner-inbox proposal card (evo_proposals / ProposalModel). */
export interface Proposal {
  id: string;
  campaignId: string;
  candidateId?: string | null;
  kind: string;
  evidence?: Record<string, unknown> | null;
  status: string;
  actor?: string | null;
  decidedAt?: string | null;
  expiresAt?: string | null;
  createdAt?: string | null;
}

/** What an approved proposal WOULD trigger — honest not-yet-armed substrate (NextSteps). */
export interface NextSteps {
  action: string;
  status: string;
  note: string;
}

/** The approve/reject response (ProposalDecision). */
export interface ProposalDecision {
  proposal: Proposal;
  nextSteps?: NextSteps | null;
}

/** One persisted §7 reconciliation row (reconciliations / ReconciliationModel). */
export interface Reconciliation {
  id: string;
  versionId?: string | null;
  strategyId?: string | null;
  windowFrom?: string | null;
  windowTo?: string | null;
  simJobId?: string | null;
  simRunId?: string | null;
  gap?: Record<string, unknown> | null;
  gapZ?: number | null;
  pairedTrades: number;
  evidenceFloorMet: boolean;
  verdict: string;
  diagnosis?: Record<string, unknown> | null;
  createdAt?: string | null;
}

/** One parameter-importance tornado entry (§3.4). */
export interface ImportanceItem {
  param: string;
  score: number;
  direction?: string | null; // positive | negative | flat
}

/** One param × stability brittleness cell (§3.4). */
export interface BrittlenessItem {
  param: string;
  importance: number;
  localVariance?: number | null;
  brittle: boolean;
}

/** One cell of a 2-D interaction slice. */
export interface SliceCell {
  x?: unknown;
  y?: unknown;
  objective?: number | null;
  count: number;
}

/** A 2-D plateau slice over two params. */
export interface InteractionSlice {
  paramX: string;
  paramY: string;
  cells: SliceCell[];
}

/** Per-sweep parameter-effect analytics (`/insights/{sweepJobId}` / InsightsResponse). */
export interface SweepInsights {
  sweepJobId: string;
  metric: string;
  direction: string; // the OPTIMIZE direction (maximize | minimize)
  importance: ImportanceItem[];
  brittleness: BrittlenessItem[];
  slices: InteractionSlice[];
  notes: string[];
}

/** Per-sweep retro-scored cohort (`/retro-score/{sweepJobId}` / RetroScoreResponse). */
export interface RetroScore {
  sweepJobId: string;
  metric: string;
  direction: string;
  policy: string;
  caveats: string[];
  items: Scorecard[];
}

interface Envelope<T> {
  items: T[];
}

const EVO_KEY = 'evolution';

// --- read hooks ---------------------------------------------------------------------------------

/** All campaigns, newest first. */
export function useCampaigns(limit = 50, offset = 0) {
  return useQuery({
    queryKey: [EVO_KEY, 'campaigns', limit, offset],
    queryFn: () =>
      apiFetch<Envelope<Campaign>>(`/evolution/campaigns?limit=${limit}&offset=${offset}`),
  });
}

/** One campaign + its generations; `enabled` gates the fetch until an id exists. */
export function useCampaign(campaignId: string | undefined) {
  return useQuery({
    queryKey: [EVO_KEY, 'campaign', campaignId],
    queryFn: () => apiFetch<CampaignDetail>(`/evolution/campaigns/${campaignId}`),
    enabled: campaignId != null && campaignId !== '',
  });
}

/** A campaign's candidates (scorecard embedded on each row). */
export function useCampaignCandidates(campaignId: string | undefined) {
  return useQuery({
    queryKey: [EVO_KEY, 'candidates', campaignId],
    queryFn: () => apiFetch<Envelope<Candidate>>(`/evolution/campaigns/${campaignId}/candidates`),
    enabled: campaignId != null && campaignId !== '',
  });
}

/** The owner inbox — proposals newest-first, filterable by status / kind / campaign. */
export function useProposals(filters: {
  status?: string | null;
  kind?: string | null;
  campaignId?: string | null;
  limit?: number;
  offset?: number;
} = {}) {
  return useQuery({
    queryKey: [EVO_KEY, 'proposals', filters],
    queryFn: () => {
      const p = new URLSearchParams();
      p.set('limit', String(filters.limit ?? 100));
      p.set('offset', String(filters.offset ?? 0));
      if (filters.status) p.set('status', filters.status);
      if (filters.kind) p.set('kind', filters.kind);
      if (filters.campaignId) p.set('campaignId', filters.campaignId);
      return apiFetch<Envelope<Proposal>>(`/evolution/proposals?${p.toString()}`);
    },
  });
}

/** A version's reconciliations, newest-first; gated until a versionId exists. */
export function useReconciliations(versionId: string | null | undefined) {
  return useQuery({
    queryKey: [EVO_KEY, 'reconciliations', versionId],
    queryFn: () => apiFetch<Envelope<Reconciliation>>(`/evolution/reconciliations?versionId=${versionId}`),
    enabled: versionId != null && versionId !== '',
  });
}

/** Per-sweep parameter-effect analytics (tornado / brittleness / slices); gated on a sweep id. */
export function useSweepInsights(sweepJobId: string | null | undefined) {
  return useQuery({
    queryKey: [EVO_KEY, 'sweep-insights', sweepJobId],
    queryFn: () => apiFetch<SweepInsights>(`/evolution/insights/${sweepJobId}`),
    enabled: sweepJobId != null && sweepJobId !== '',
  });
}

/** A sweep's retro-scored cohort (descriptive league table); gated on a sweep id. */
export function useRetroScore(sweepJobId: string | null | undefined) {
  return useQuery({
    queryKey: [EVO_KEY, 'retro-score', sweepJobId],
    queryFn: () => apiFetch<RetroScore>(`/evolution/retro-score/${sweepJobId}`),
    enabled: sweepJobId != null && sweepJobId !== '',
  });
}

// --- owner-clicked writes (approve / reject) ----------------------------------------------------
// The ONLY mutations exposed. Approve/reject transition the proposal + write an audit row (actor
// only — the backend reads no note); neither arms live behaviour (the PROMOTE/PUBLISH execute is a
// separate owner step, deliberately not surfaced here).

function useProposalDecision(kind: 'approve' | 'reject') {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<ProposalDecision>(`/evolution/proposals/${id}/${kind}`, { method: 'POST', json: {} }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [EVO_KEY] });
    },
  });
}

/** Approve a PENDING proposal (status → APPROVED + audit row). Arms nothing. */
export function useApproveProposal() {
  return useProposalDecision('approve');
}

/** Reject a PENDING proposal (status → REJECTED + audit row; §8.3 never silent). */
export function useRejectProposal() {
  return useProposalDecision('reject');
}

// --- pure helpers (tested) ----------------------------------------------------------------------

/** Human labels for the §6.2 RobustScore component ids. */
export const COMPONENT_LABELS: Record<string, string> = {
  oos_return: 'OOS return',
  stability: 'Stability',
  risk_adjusted: 'Risk-adjusted',
  drawdown_quality: 'Drawdown quality',
  regime_consistency: 'Regime consistency',
  cost_resilience: 'Cost resilience',
  live_alignment: 'Live alignment',
  explainability: 'Explainability',
  efficiency: 'Efficiency',
};

/** Human labels for the §6.1 hard-gate ids. */
export const GATE_LABELS: Record<string, string> = {
  evidence_floor: 'Evidence floor',
  oos_sign: 'OOS sign',
  fold_consistency: 'Fold consistency',
  drawdown_cap: 'Drawdown cap',
  regime_floor: 'Regime floor',
  stability_floor: 'Stability floor',
  holdout: 'Holdout',
  comparability: 'Comparability',
  live_gap: 'Live-gap',
  multiplicity_tstat: 'Multiplicity t-stat',
};

/** The main §8.1 lifecycle order for the pipeline strip (terminal RETIRED/ROLLED_BACK excluded). */
export const PIPELINE_STAGES: CandidateState[] = [
  'PROPOSED',
  'EVALUATING',
  'SCORED',
  'SURVIVOR',
  'PAPER',
  'TAKE_ELIGIBLE',
  'PROMOTED',
];

/** A gate is "green" when PASS; SKIPPED/UNKNOWN/NOT_IMPLEMENTED are neutral (not failures). */
export function isGatePass(status: string): boolean {
  return status === 'PASS';
}
export function isGateFail(status: string): boolean {
  return status === 'FAIL';
}

/** Summarize a scorecard's gate row: passed / failed / total, plus the failed gate ids. */
export function gateSummary(gates: GateResult[] | undefined): {
  passed: number;
  failed: number;
  total: number;
  failedIds: string[];
} {
  const list = gates ?? [];
  const passed = list.filter((g) => isGatePass(g.status)).length;
  const failedIds = list.filter((g) => isGateFail(g.status)).map((g) => g.id);
  return { passed, failed: failedIds.length, total: list.length, failedIds };
}

/** True iff a scorecard clears every hard gate (no FAIL) — the "all green" review flag. */
export function allGatesGreen(gates: GateResult[] | undefined): boolean {
  return gateSummary(gates).failed === 0 && (gates?.length ?? 0) > 0;
}

/**
 * The signed per-component contribution to RobustScore: weight × z (design §6.2). Returned sorted
 * by descending magnitude so the stacked breakdown reads dominant-first. Missing weight → 0.
 */
export function robustScoreContribs(
  card: Scorecard | null | undefined,
): { id: string; label: string; z: number; weight: number; contribution: number }[] {
  if (!card?.components) return [];
  const weights = card.weights ?? {};
  return card.components
    .map((c) => {
      const weight = weights[c.id] ?? 0;
      return {
        id: c.id,
        label: COMPONENT_LABELS[c.id] ?? c.id,
        z: c.z,
        weight,
        contribution: weight * c.z,
      };
    })
    .sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution));
}

/** Count candidates per pipeline stage (PIPELINE_STAGES order), plus the two terminal branches. */
export function pipelineCounts(candidates: Candidate[]): {
  stages: { stage: CandidateState; count: number }[];
  retired: number;
  rolledBack: number;
} {
  const tally = new Map<string, number>();
  for (const c of candidates) {
    const s = c.state ?? 'PROPOSED';
    tally.set(s, (tally.get(s) ?? 0) + 1);
  }
  return {
    stages: PIPELINE_STAGES.map((stage) => ({ stage, count: tally.get(stage) ?? 0 })),
    retired: tally.get('RETIRED') ?? 0,
    rolledBack: tally.get('ROLLED_BACK') ?? 0,
  };
}

/** The best-challenger RobustScore in a cohort (max over scored candidates); null if none scored. */
export function bestChallengerScore(candidates: Candidate[]): number | null {
  let best: number | null = null;
  for (const c of candidates) {
    const s = c.scorecard?.robustScore;
    if (typeof s === 'number' && (best == null || s > best)) best = s;
  }
  return best;
}

/** Format a signed number to a fixed dp with an explicit + sign (score deltas / z's). */
export function fmtSigned(v: number | null | undefined, dp = 2): string {
  if (v == null || Number.isNaN(v)) return '—';
  const s = v.toLocaleString('en-IN', { minimumFractionDigits: dp, maximumFractionDigits: dp });
  return v > 0 ? `+${s}` : s;
}

/** Format a plain number to a fixed dp (— for null/NaN). */
export function fmtNum(v: number | null | undefined, dp = 2): string {
  if (v == null || Number.isNaN(v)) return '—';
  return v.toLocaleString('en-IN', { maximumFractionDigits: dp });
}

/** IST day/month + HH:MM for a wire ISO timestamp (— for a null/absent value). */
export function fmtIstTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
}

/**
 * A compact "expires in Nd/Nh" (or "expired") for a proposal's 7-day expiry countdown. `null` iso →
 * null (no expiry stamped). Past → "expired".
 */
export function expiryCountdown(iso: string | null | undefined, now: number = Date.now()): string | null {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - now;
  if (!Number.isFinite(ms)) return null;
  if (ms <= 0) return 'expired';
  const h = Math.floor(ms / 3_600_000);
  if (h < 24) return `expires in ${h}h`;
  return `expires in ${Math.floor(h / 24)}d`;
}

/** The last URL/UUID path segment, shortened to its trailing 8 chars for a dense id chip. */
export function shortId(id: string | null | undefined): string {
  if (!id) return '—';
  const tail = id.replace(/[^a-zA-Z0-9]/g, '');
  return tail.length > 8 ? `…${tail.slice(-8)}` : id;
}

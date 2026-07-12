import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Check, Inbox, X } from 'lucide-react';
import { Button } from '../../components/atoms/Button.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import {
  expiryCountdown,
  fmtIstTime,
  PROPOSAL_KINDS,
  PROPOSAL_STATUSES,
  shortId,
  useApproveProposal,
  useProposals,
  useRejectProposal,
  type Proposal,
} from '../../api/evolution.ts';
import { ProposalKindBadge, ProposalStatusBadge } from './EvolutionBits.tsx';

// /evolution/proposals — the owner approval inbox (design §10 route 3). PENDING gate proposals carry
// Approve / Reject (the §11.6 checkpoint workflow's first concrete instance); RETIRE rows are
// autonomous auto-applied acknowledge items (§8.2 — never a pending gate, so they render read-only).
// Approve/reject transition the row + write an audit row; neither arms live behaviour — the PROMOTE /
// PUBLISH execute is a separate explicit owner step, intentionally not exposed on this screen.

// Read the well-known §8.2 proposal-card keys the backend actually writes (`_evidence_card` /
// `_promote_evidence` in proposals.py): whatChanges, robustScore + championRobustScore + margin, the
// gates ARRAY ({id,status}), holdoutConsumed, the blastRadius OBJECT ({family,plane}), the rollback
// plan/target, and the honest "nothing self-arms" note. Unknown shapes are left to the drill-down.
function num(v: unknown): string | null {
  return typeof v === 'number' && !Number.isNaN(v)
    ? v.toLocaleString('en-IN', { maximumFractionDigits: 3 })
    : null;
}

function EvidenceSummary({ evidence }: { evidence: Record<string, unknown> | null | undefined }) {
  if (!evidence) return null;
  const rows: { label: string; value: string }[] = [];

  const whatChanges = typeof evidence.whatChanges === 'string' ? evidence.whatChanges : null;

  const robust = num(evidence.robustScore);
  const champ = num(evidence.championRobustScore);
  if (robust) rows.push({ label: 'RobustScore', value: champ ? `${robust} (champion ${champ})` : robust });
  const margin = num(evidence.margin);
  if (margin) rows.push({ label: 'Δ vs champion', value: margin });

  // gates is an array of {id, status}; summarize green/total.
  if (Array.isArray(evidence.gates)) {
    const gates = evidence.gates as { status?: string }[];
    const green = gates.filter((g) => g.status === 'PASS').length;
    rows.push({ label: 'Gates', value: `${green}/${gates.length} green` });
  }
  if (typeof evidence.holdoutConsumed === 'boolean') {
    rows.push({ label: 'Holdout', value: evidence.holdoutConsumed ? 'consumed' : 'not consumed' });
  }
  // blastRadius is {family, plane}.
  if (evidence.blastRadius && typeof evidence.blastRadius === 'object' && !Array.isArray(evidence.blastRadius)) {
    const br = evidence.blastRadius as Record<string, unknown>;
    const parts = [br.family, br.plane].filter((v) => typeof v === 'string');
    if (parts.length > 0) rows.push({ label: 'Blast radius', value: parts.join(' · ') });
  }
  const rollback =
    (typeof evidence.rollbackPlan === 'string' && evidence.rollbackPlan) ||
    (typeof evidence.rollbackTarget === 'string' && evidence.rollbackTarget) ||
    null;

  const pendingInputs = Array.isArray(evidence.pendingInputs)
    ? (evidence.pendingInputs as unknown[]).filter((v) => typeof v === 'string')
    : [];

  if (!whatChanges && rows.length === 0 && !rollback) return null;
  return (
    <div className="flex flex-col gap-1.5">
      {whatChanges && <p className="text-[11px] text-ay-text">{whatChanges}</p>}
      {rows.length > 0 && (
        <dl className="grid grid-cols-1 gap-x-4 gap-y-1 text-[11px] sm:grid-cols-2">
          {rows.map((r, i) => (
            <div key={i} className="flex gap-1.5">
              <dt className="shrink-0 text-ay-muted">{r.label}:</dt>
              <dd className="text-ay-text">{r.value}</dd>
            </div>
          ))}
        </dl>
      )}
      {rollback && <p className="text-[11px] text-ay-muted">Rollback: {rollback}</p>}
      {pendingInputs.length > 0 && (
        <p className="text-[11px] text-warn">Pending inputs: {pendingInputs.join('; ')}</p>
      )}
    </div>
  );
}

function ProposalCard({ proposal }: { proposal: Proposal }) {
  const approve = useApproveProposal();
  const reject = useRejectProposal();
  const isPending = proposal.status === 'PENDING';
  const isRetire = proposal.kind === 'RETIRE';
  // Only a PENDING, non-RETIRE proposal is an actionable owner gate (§8.2 — RETIRE is autonomous).
  const actionable = isPending && !isRetire;
  const countdown = expiryCountdown(proposal.expiresAt);
  const busy = approve.isPending || reject.isPending;

  return (
    <div className="flex flex-col gap-2.5 rounded-lg border border-ay-border bg-surface-1 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <ProposalKindBadge kind={proposal.kind} />
        <ProposalStatusBadge status={proposal.status} />
        {isRetire && (
          <span className="text-[11px] text-ay-muted" title="Autonomous — auto-applied, acknowledge only">
            auto-applied
          </span>
        )}
        <span className="ml-auto text-[11px] text-ay-muted">
          {countdown ? `${countdown} · ` : ''}filed {fmtIstTime(proposal.createdAt)}
        </span>
      </div>

      <div className="text-caption text-ay-muted">
        Campaign{' '}
        <Link to={`/evolution/${proposal.campaignId}`} className="text-accent hover:underline">
          {shortId(proposal.campaignId)}
        </Link>
        {proposal.candidateId && (
          <>
            {' · '}candidate <code className="text-ay-text">{shortId(proposal.candidateId)}</code>
          </>
        )}
        {proposal.actor && proposal.status !== 'PENDING' && (
          <>
            {' · '}by {proposal.actor} {proposal.decidedAt ? `(${fmtIstTime(proposal.decidedAt)})` : ''}
          </>
        )}
      </div>

      <EvidenceSummary evidence={proposal.evidence} />

      {actionable && (
        <div className="flex flex-wrap items-center gap-2 border-t border-ay-border pt-3">
          <Button
            variant="primary"
            size="sm"
            icon={Check}
            loading={approve.isPending}
            disabled={busy}
            onClick={() => approve.mutate(proposal.id)}
          >
            Approve
          </Button>
          <Button
            variant="outline"
            size="sm"
            icon={X}
            loading={reject.isPending}
            disabled={busy}
            onClick={() => reject.mutate(proposal.id)}
          >
            Reject
          </Button>
          <span className="text-[11px] text-ay-muted">
            Owner-gated. Approving records the decision; it does not itself arm live behaviour.
          </span>
        </div>
      )}
      {(approve.isError || reject.isError) && (
        <p role="alert" className="text-[11px] text-bear">
          Couldn't record the decision — it may already be decided or expired. Refresh and retry.
        </p>
      )}
    </div>
  );
}

const KIND_ORDER = ['PROMOTE', 'ROLLBACK', 'PUBLISH_PAPER', 'REVIEW_GATE', 'RETIRE'];

export function ProposalsInboxPage() {
  const [searchParams] = useSearchParams();
  const campaignId = searchParams.get('campaignId');
  const [status, setStatus] = useState('PENDING');
  const [kind, setKind] = useState('');

  const q = useProposals({
    status: status || null,
    kind: kind || null,
    campaignId,
    limit: 200,
  });

  const statusOptions = [{ value: '', label: 'All statuses' }, ...PROPOSAL_STATUSES.map((s) => ({ value: s, label: s }))];
  const kindOptions = [{ value: '', label: 'All kinds' }, ...PROPOSAL_KINDS.map((k) => ({ value: k, label: k.replace(/_/g, ' ') }))];

  return (
    <div>
      <PageHeader
        title="Proposals inbox"
        subtitle="Owner-gated decisions — approve or reject each candidate's move toward live"
        help="Every proposal the evolution engine files: PUBLISH_PAPER (earn live paper evidence), PROMOTE (become the live champion), ROLLBACK, and REVIEW_GATE suggestions. Approving records your decision and its audit trail — it never itself arms live trading; the publish/promote execution is a separate explicit step. RETIRE items are autonomous (auto-applied) and shown for acknowledgement only."
        right={
          <Link
            to="/evolution"
            className="inline-flex items-center gap-1.5 rounded-md border border-ay-border px-2.5 py-1.5 text-body-sm text-ay-muted hover:border-accent"
          >
            Campaign board
          </Link>
        }
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Select value={status} ariaLabel="Filter by status" options={statusOptions} onChange={setStatus} />
        <Select value={kind} ariaLabel="Filter by kind" options={kindOptions} onChange={setKind} />
        {campaignId && (
          <span className="text-[11px] text-ay-muted">
            filtered to campaign <code className="text-ay-text">{shortId(campaignId)}</code>
          </span>
        )}
      </div>

      <QueryState
        query={q}
        skeleton={<Skeleton variant="card" />}
        errorTitle="Couldn't load proposals"
        empty={{
          icon: Inbox,
          title: status === 'PENDING' ? 'No pending proposals.' : 'No proposals match this filter.',
          hint: 'Proposals appear here as the engine surfaces owner-gated decisions.',
        }}
      >
        {(data) => {
          const grouped = KIND_ORDER.map((k) => ({
            kind: k,
            items: data.items.filter((p) => p.kind === k),
          })).filter((g) => g.items.length > 0);
          // Any kind not in KIND_ORDER (defensive) rides an "Other" bucket.
          const known = new Set(KIND_ORDER);
          const other = data.items.filter((p) => !known.has(p.kind));
          return (
            <div className="flex flex-col gap-6">
              {grouped.map((g) => (
                <section key={g.kind} aria-label={`${g.kind} proposals`}>
                  <h2 className="mb-2 flex items-center gap-2 text-caption font-semibold uppercase tracking-wide text-ay-muted">
                    <ProposalKindBadge kind={g.kind} />
                    <span>{g.items.length}</span>
                  </h2>
                  <div className="flex flex-col gap-3">
                    {g.items.map((p) => (
                      <ProposalCard key={p.id} proposal={p} />
                    ))}
                  </div>
                </section>
              ))}
              {other.length > 0 && (
                <section aria-label="Other proposals">
                  <div className="flex flex-col gap-3">
                    {other.map((p) => (
                      <ProposalCard key={p.id} proposal={p} />
                    ))}
                  </div>
                </section>
              )}
            </div>
          );
        }}
      </QueryState>
    </div>
  );
}

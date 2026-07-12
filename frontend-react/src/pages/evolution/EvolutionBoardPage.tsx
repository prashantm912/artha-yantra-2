import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { FlaskConical, Inbox } from 'lucide-react';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import {
  shortId,
  useCampaigns,
  useProposals,
  type Campaign,
} from '../../api/evolution.ts';
import { CampaignStatusBadge, PolicyBadge } from './EvolutionBits.tsx';

// /evolution — the campaign board (design §10 route 1). One card per evolution program: base
// strategy, family + policy + status badges, whether a champion is set, the budget envelope, and a
// pending-proposals chip (from one shared PENDING query, grouped by campaign — no per-card fan-out).
// The generation count + champion-vs-challenger detail lives in the workspace (it needs the campaign's
// candidates, which the list payload does not carry). A banner links straight to the owner inbox.

function budgetSummary(budget: Record<string, unknown> | null | undefined): string {
  if (!budget) return 'no budget set';
  const parts: string[] = [];
  const num = (k: string) => (typeof budget[k] === 'number' ? (budget[k] as number) : null);
  const g = num('maxGenerations');
  const t = num('maxTrialsPerGen') ?? num('maxTrials');
  const h = num('holdoutTouches');
  const cadence = typeof budget.cadence === 'string' ? budget.cadence : null;
  if (g != null) parts.push(`${g} gens`);
  if (t != null) parts.push(`${t} trials/gen`);
  if (h != null) parts.push(`${h} holdout`);
  if (cadence) parts.push(cadence);
  return parts.length > 0 ? parts.join(' · ') : 'budget set';
}

function CampaignCard({ campaign, pending }: { campaign: Campaign; pending: number }) {
  return (
    <Link
      to={`/evolution/${campaign.id}`}
      className="flex flex-col gap-2.5 rounded-lg border border-ay-border bg-surface-1 p-4 transition-colors hover:border-accent"
    >
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="text-body font-semibold text-ay-text">
            {campaign.family ?? 'Evolution campaign'}
          </div>
          <div className="text-caption text-ay-muted">
            base <code className="text-ay-text">{shortId(campaign.strategyId)}</code>
          </div>
        </div>
        <CampaignStatusBadge status={campaign.status} />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <PolicyBadge policy={campaign.evidencePolicy} />
        {campaign.championVersionId ? (
          <span className="rounded px-1.5 py-0.5 text-[11px] text-bull ring-1 ring-bull/40">
            champion set
          </span>
        ) : (
          <span className="rounded px-1.5 py-0.5 text-[11px] text-ay-muted ring-1 ring-ay-border">
            no champion yet
          </span>
        )}
        {pending > 0 && (
          <span className="rounded px-1.5 py-0.5 text-[11px] font-medium text-warn ring-1 ring-warn/40">
            {pending} pending proposal{pending === 1 ? '' : 's'}
          </span>
        )}
      </div>

      <div className="mt-0.5 border-t border-ay-border/50 pt-2 text-[11px] text-ay-muted">
        Budget: {budgetSummary(campaign.budget)}
      </div>
    </Link>
  );
}

export function EvolutionBoardPage() {
  const campaignsQ = useCampaigns();
  const pendingQ = useProposals({ status: 'PENDING' });

  const pendingByCampaign = useMemo(() => {
    const m = new Map<string, number>();
    for (const p of pendingQ.data?.items ?? []) m.set(p.campaignId, (m.get(p.campaignId) ?? 0) + 1);
    return m;
  }, [pendingQ.data]);
  const totalPending = pendingQ.data?.items.length ?? 0;

  return (
    <div>
      <PageHeader
        title="Evolution"
        subtitle="Autonomous strategy-evolution campaigns — search, score, and the owner-gated path to live"
        help="Each campaign evolves one base strategy: propose tuned candidates, score them on out-of-sample and live evidence (hard gates + RobustScore), and surface owner-gated proposals to move a survivor toward live paper. Open a campaign for its leaderboard, candidate scorecards, parameter insights and generation history. Nothing here arms live behaviour — every promotion is an explicit owner approval."
        right={
          totalPending > 0 ? (
            <Link
              to="/evolution/proposals"
              className="inline-flex items-center gap-1.5 rounded-md border border-warn/40 px-2.5 py-1.5 text-body-sm text-warn hover:bg-warn/5"
            >
              <Inbox aria-hidden="true" className="size-4" />
              {totalPending} pending proposal{totalPending === 1 ? '' : 's'}
            </Link>
          ) : (
            <Link
              to="/evolution/proposals"
              className="inline-flex items-center gap-1.5 rounded-md border border-ay-border px-2.5 py-1.5 text-body-sm text-ay-muted hover:border-accent"
            >
              <Inbox aria-hidden="true" className="size-4" />
              Proposals inbox
            </Link>
          )
        }
      />

      <QueryState
        query={campaignsQ}
        skeleton={<Skeleton variant="card" />}
        errorTitle="Couldn't load campaigns"
        empty={{
          icon: FlaskConical,
          title: 'No evolution campaigns yet.',
          hint: 'A campaign is created by the owner against a base strategy; it will appear here once launched.',
        }}
      >
        {(data) => (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {data.items.map((c) => (
              <CampaignCard key={c.id} campaign={c} pending={pendingByCampaign.get(c.id) ?? 0} />
            ))}
          </div>
        )}
      </QueryState>
    </div>
  );
}

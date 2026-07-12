import { useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import {
  bestChallengerScore,
  fmtNum,
  fmtSigned,
  shortId,
  useCampaign,
  useCampaignCandidates,
  useProposals,
  type Candidate,
} from '../../api/evolution.ts';
import { CampaignStatusBadge, PolicyBadge } from './EvolutionBits.tsx';
import { PipelineStrip } from './PipelineStrip.tsx';
import { Leaderboard } from './Leaderboard.tsx';
import { InsightsView } from './InsightsView.tsx';
import { GenerationsTimeline } from './GenerationsTimeline.tsx';
import { CandidateCardDrawer } from './CandidateCardDrawer.tsx';

// /evolution/:campaignId — the campaign workspace (design §10 route 2). A header + summary strip +
// the promotion pipeline, then the four artifact surfaces as tabs: Leaderboard, Candidate card
// (a drawer opened from the leaderboard, not a fifth tab), Insights and Generations. The tab lives in
// the URL (?tab=) so a view is shareable/bookmarkable.

const TABS = [
  { id: 'leaderboard', label: 'Leaderboard' },
  { id: 'insights', label: 'Insights' },
  { id: 'generations', label: 'Generations' },
] as const;
type TabId = (typeof TABS)[number]['id'];

export function CampaignWorkspacePage() {
  const { campaignId } = useParams<{ campaignId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = (searchParams.get('tab') as TabId) ?? 'leaderboard';
  const setTab = (t: TabId) => setSearchParams((p) => {
    p.set('tab', t);
    return p;
  }, { replace: true });

  const detailQ = useCampaign(campaignId);
  const candidatesQ = useCampaignCandidates(campaignId);
  const proposalsQ = useProposals({ campaignId, status: 'PENDING' });

  const [drawerCandidate, setDrawerCandidate] = useState<Candidate | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const openDrawer = (c: Candidate) => {
    setDrawerCandidate(c);
    setDrawerOpen(true);
  };

  const candidates = useMemo(() => candidatesQ.data?.items ?? [], [candidatesQ.data]);
  const detail = detailQ.data;
  const generations = detail?.generations ?? [];

  const championScore = useMemo(() => {
    const champ = candidates.find((c) => c.versionId === detail?.championVersionId);
    return champ?.scorecard?.robustScore ?? null;
  }, [candidates, detail?.championVersionId]);
  const bestChallenger = useMemo(() => bestChallengerScore(candidates), [candidates]);
  const pendingCount = proposalsQ.data?.items.length ?? 0;

  return (
    <div>
      <Link
        to="/evolution"
        className="mb-2 inline-flex items-center gap-1 text-caption text-ay-muted hover:text-accent"
      >
        <ArrowLeft aria-hidden="true" className="size-3.5" /> All campaigns
      </Link>

      <QueryState query={detailQ} skeleton={<Skeleton variant="card" />} errorTitle="Couldn't load campaign">
        {(campaign) => (
          <>
            <PageHeader
              title={campaign.family ? `${campaign.family} evolution` : 'Evolution campaign'}
              subtitle={
                <>
                  Base strategy <code className="text-ay-text">{shortId(campaign.strategyId)}</code> · campaign{' '}
                  <code className="text-ay-text">{shortId(campaign.id)}</code>
                </>
              }
              help="One long-lived evolution program for a base strategy. Candidates are concrete tuned versions; each carries a scorecard (hard gates + RobustScore). Survivors earn live paper evidence; the owner approves any move toward live from the Proposals inbox. Nothing here arms live behaviour."
              right={
                <div className="flex flex-wrap items-center gap-2">
                  <PolicyBadge policy={campaign.evidencePolicy} />
                  <CampaignStatusBadge status={campaign.status} />
                  {pendingCount > 0 && (
                    <Link
                      to={`/evolution/proposals?campaignId=${campaign.id}`}
                      className="rounded px-1.5 py-0.5 text-[11px] font-medium text-warn ring-1 ring-warn/40 hover:bg-warn/5"
                    >
                      {pendingCount} pending
                    </Link>
                  )}
                </div>
              }
            />

            {/* Summary strip. */}
            <div className="mb-4 flex flex-wrap gap-2">
              <Metric label="Generations" value={String(generations.length)} />
              <Metric label="Candidates" value={String(candidates.length)} />
              <Metric label="Champion" value={fmtNum(championScore, 3)} title="Incumbent champion RobustScore" />
              <Metric label="Best challenger" value={fmtNum(bestChallenger, 3)} />
              {championScore != null && bestChallenger != null && (
                <Metric
                  label="Δ best vs champ"
                  value={fmtSigned(bestChallenger - championScore)}
                  title="Best-challenger RobustScore minus champion"
                />
              )}
            </div>

            {/* Promotion pipeline strip (§10 item 4). */}
            <div className="mb-5 rounded-lg border border-ay-border bg-surface-1 p-3">
              <h2 className="mb-2 text-caption font-semibold uppercase tracking-wide text-ay-muted">
                Promotion pipeline
              </h2>
              <PipelineStrip candidates={candidates} />
            </div>

            {/* Tabs. */}
            <div role="tablist" aria-label="Campaign views" className="mb-4 flex gap-1 border-b border-ay-border">
              {TABS.map((t) => (
                <button
                  key={t.id}
                  role="tab"
                  type="button"
                  aria-selected={tab === t.id}
                  onClick={() => setTab(t.id)}
                  className={cn(
                    '-mb-px border-b-2 px-3 py-2 text-body-sm font-medium',
                    tab === t.id
                      ? 'border-accent text-accent'
                      : 'border-transparent text-ay-muted hover:text-ay-text',
                  )}
                >
                  {t.label}
                </button>
              ))}
            </div>

            <QueryState
              query={candidatesQ}
              skeleton={<Skeleton variant="table-rows" />}
              errorTitle="Couldn't load candidates"
              isEmpty={() => false}
            >
              {() => (
                <div>
                  {tab === 'leaderboard' && (
                    <Leaderboard
                      candidates={candidates}
                      generations={generations}
                      championVersionId={campaign.championVersionId}
                      onOpen={openDrawer}
                    />
                  )}
                  {tab === 'insights' && (
                    <InsightsView generations={generations} candidates={candidates} />
                  )}
                  {tab === 'generations' && (
                    <GenerationsTimeline generations={generations} candidates={candidates} />
                  )}
                </div>
              )}
            </QueryState>
          </>
        )}
      </QueryState>

      <CandidateCardDrawer candidate={drawerCandidate} open={drawerOpen} onOpenChange={setDrawerOpen} />
    </div>
  );
}

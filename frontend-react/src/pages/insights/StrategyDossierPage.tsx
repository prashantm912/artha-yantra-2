import { Link, useParams } from 'react-router-dom';
import { GraduationCap } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import { SeverityBadge } from '../../components/insights/InsightBits.tsx';
import { useDossier, type Criterion, type Dossier, type InsightSeverity } from '../../api/insights.ts';

// /insights/strategy-dossier/:strategyId (INT design §5.1) — the qualification dossier: the join the
// owner does by hand across the graduation, signals, sell-decision and STRATEGY_EVIDENCE pages,
// server-assembled into one view. Graduation criteria with pass-map NOW, the threshold-crossing
// timeline (the history the static board lacks, §5.2), the rejection profile (top blocking rails), and
// open sell-decisions (§5.3). Assembly-only + read-only — nothing here promotes, arms or closes
// anything. Reachable from the graduation board and from strategy-scoped insight cards.

function fmtTime(iso?: string | null): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
}

/** The stage pill — TAKE_ELIGIBLE reads bullish, PAPER neutral, UNLISTED muted. */
function StageBadge({ stage }: { stage?: string }) {
  const eligible = stage === 'TAKE_ELIGIBLE';
  return (
    <span
      className={cn(
        'rounded px-1.5 py-0.5 text-xs font-semibold ring-1',
        eligible ? 'text-bull ring-bull/40' : 'text-ay-muted ring-ay-border',
      )}
    >
      {stage ?? '—'}
    </span>
  );
}

/** One criterion: a pass/fail dot + "actual (needs required)". */
function CriterionRow({ c }: { c: Criterion }) {
  return (
    <li className="flex items-center gap-2 py-1 text-body-sm">
      <span
        aria-label={c.pass ? 'pass' : 'fail'}
        className={cn('inline-block size-2.5 shrink-0 rounded-full', c.pass ? 'bg-bull' : 'bg-bear')}
      />
      <span className="text-ay-text">{c.name}</span>
      <span className="ml-auto tabular-nums text-ay-muted">
        {c.actual ?? '—'} <span className="text-ay-muted/70">(needs {c.required ?? '—'})</span>
      </span>
    </li>
  );
}

function DossierBody({ d }: { d: Dossier }) {
  const criteria = d.criteria ?? [];
  const timeline = d.crossingTimeline ?? [];
  const rails = d.rejectionProfile ?? [];
  const sells = d.openSellDecisions ?? [];
  const maxRail = rails.reduce((m, r) => Math.max(m, r.count ?? 0), 0);

  return (
    <BeatBlock className="flex flex-col gap-5">
      {/* Identity + stage. */}
      <section className="flex flex-wrap items-center gap-3 rounded-lg border border-ay-border bg-surface-1 p-3">
        <div className="min-w-0">
          <div className="text-h3 text-ay-text">{d.name ?? d.slug ?? d.strategyId}</div>
          {d.slug && <div className="text-caption text-ay-muted">{d.slug}</div>}
        </div>
        <span className="ml-auto flex flex-wrap items-center gap-2">
          <StageBadge stage={d.stage} />
          {d.graduatedAt && (
            <span className="rounded px-1.5 py-0.5 text-xs font-semibold text-bull ring-1 ring-bull/40">
              Graduated {fmtTime(d.graduatedAt)}
            </span>
          )}
          {d.enabled === false && (
            <span className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-muted">disabled</span>
          )}
          <Link to="/strategies/graduation" className="text-caption text-accent hover:underline">
            Graduation board →
          </Link>
        </span>
      </section>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
        {/* Graduation criteria. */}
        <section className="rounded-lg border border-ay-border bg-surface-1 p-3">
          <h2 className="mb-1 text-caption font-semibold uppercase tracking-wide text-ay-muted">
            Graduation criteria
          </h2>
          {criteria.length > 0 ? (
            <ul className="divide-y divide-ay-border/40">
              {criteria.map((c) => (
                <CriterionRow key={c.name} c={c} />
              ))}
            </ul>
          ) : (
            <p className="text-body-sm text-ay-muted">
              Not on the live graduation board — criteria are only scored for a published, enabled strategy.
            </p>
          )}
        </section>

        {/* Threshold-crossing timeline. */}
        <section className="rounded-lg border border-ay-border bg-surface-1 p-3">
          <h2 className="mb-1 text-caption font-semibold uppercase tracking-wide text-ay-muted">
            Threshold-crossing timeline
          </h2>
          {timeline.length > 0 ? (
            <ul className="flex flex-col gap-1.5">
              {timeline.map((t, i) => (
                <li key={i} className="flex items-start gap-2 text-body-sm">
                  <SeverityBadge severity={(t.severity as InsightSeverity) ?? 'INFO'} />
                  <span className="min-w-0 flex-1 text-ay-text">{t.title}</span>
                  <span className="shrink-0 whitespace-nowrap text-caption text-ay-muted">{fmtTime(t.at)}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-body-sm text-ay-muted">
              No crossings recorded yet — the nightly STRATEGY_EVIDENCE sweep builds this history over time.
            </p>
          )}
        </section>

        {/* Rejection profile. */}
        <section className="rounded-lg border border-ay-border bg-surface-1 p-3">
          <h2 className="mb-1 text-caption font-semibold uppercase tracking-wide text-ay-muted">
            Rejection profile <span className="text-ay-muted/70">· top blocking rails (30d)</span>
          </h2>
          {rails.length > 0 ? (
            <ul className="flex flex-col gap-1.5">
              {rails.map((r, i) => (
                <li key={i} className="flex items-center gap-2 text-body-sm">
                  <span className="w-40 shrink-0 truncate text-ay-text" title={r.rail ?? ''}>
                    {r.rail ?? '—'}
                  </span>
                  <span className="h-2 flex-1 overflow-hidden rounded bg-surface-2" aria-hidden="true">
                    <span
                      className="block h-full rounded bg-accent/60"
                      style={{ width: maxRail > 0 ? `${((r.count ?? 0) / maxRail) * 100}%` : '0%' }}
                    />
                  </span>
                  <span className="w-10 shrink-0 text-right tabular-nums text-ay-muted">{r.count ?? 0}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-body-sm text-ay-muted">No rejections recorded in the last 30 sessions.</p>
          )}
        </section>

        {/* Open sell-decisions. */}
        <section className="rounded-lg border border-ay-border bg-surface-1 p-3">
          <h2 className="mb-1 text-caption font-semibold uppercase tracking-wide text-ay-muted">
            Sell-decisions
          </h2>
          {sells.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-ay-muted">
                  <tr>
                    <th className="py-1 pr-2 font-medium">Date</th>
                    <th className="py-1 pr-2 font-medium">Symbol</th>
                    <th className="py-1 pr-2 font-medium">Verdict</th>
                    <th className="py-1 pr-2 text-right font-medium">Unrealized</th>
                    <th className="py-1 font-medium">Acked</th>
                  </tr>
                </thead>
                <tbody>
                  {sells.map((s) => (
                    <tr key={s.sellDecisionId} className="border-t border-ay-border/40">
                      <td className="py-1 pr-2 tabular-nums text-ay-muted">{s.runDate ?? '—'}</td>
                      <td className="py-1 pr-2 text-ay-text">{s.symbol ?? '—'}</td>
                      <td className="py-1 pr-2 text-ay-text">{s.verdict ?? '—'}</td>
                      <td
                        className={cn(
                          'py-1 pr-2 text-right tabular-nums',
                          s.unrealizedPct != null && s.unrealizedPct < 0 ? 'text-bear' : 'text-bull',
                        )}
                      >
                        {s.unrealizedPct == null ? '—' : `${s.unrealizedPct.toFixed(2)}%`}
                      </td>
                      <td className="py-1 text-caption text-ay-muted">{s.acknowledged ? 'yes' : 'no'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-body-sm text-ay-muted">No open sell-decisions (swing strategies only).</p>
          )}
        </section>
      </div>

      <p className="text-caption text-ay-muted">
        Assembled {fmtTime(d.asOf)} IST.
        {d.notes && d.notes.length > 0 && ` ${d.notes.join(' ')}`}
      </p>
    </BeatBlock>
  );
}

export function StrategyDossierPage() {
  const { strategyId } = useParams<{ strategyId: string }>();
  const q = useDossier(strategyId ?? null);

  return (
    <LoadBeat>
      <PageHeader
        title="Strategy dossier"
        subtitle="Graduation evidence, threshold-crossing history, rejection profile and open sell-decisions — one view"
        help="Everything the intelligence layer knows about one strategy's readiness, assembled from the graduation board, its nightly evidence snapshots, its rejection history and its sell-decisions. The criteria show what passes now; the timeline is the threshold-crossing history the static board can't show; the rejection profile is its top blocking rails; the sell-decisions list open exits. Read-only — graduating, arming or closing anything stays an owner action."
      />
      <QueryState
        query={q}
        isEmpty={(d) => d.strategyId == null}
        empty={{ icon: GraduationCap, title: 'No dossier for that strategy.' }}
        errorTitle="Couldn't load the strategy dossier"
        skeleton={<Skeleton variant="table-rows" />}
      >
        {(data) => <DossierBody d={data} />}
      </QueryState>
    </LoadBeat>
  );
}

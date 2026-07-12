import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { AlertTriangle, GitCompareArrows } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import { BandChip } from '../../components/insights/InsightBits.tsx';
import { TrustChip } from '../../components/insights/TrustChip.tsx';
import { ApiError } from '../../api/client.ts';
import { useCompare, type CompareColumn, type CompareResult, type InsightTrust } from '../../api/insights.ts';

// /insights/compare?signalIds=a,b,c (INT design §4.1 / §8.5) — the decide-BETWEEN matrix. Signals are
// columns, the priority components + cost / R:R / trust are rows, the best cell per numeric row is
// highlighted, and the server's "differs mainly on X" spread marker is pinned on top. URL-addressable
// from day one (the P1-§4.2.4 convention) so a comparison is bookmarkable/shareable. Margin is shown
// HONESTLY as "unpriced" (never 0) — the backend keeps the column honest rather than fabricating a
// number. Reads only; every mutation stays behind the /act PROPOSE flow on the feed/drawer.

/** Parse `?signalIds=1,2,3` (or repeated params) into a de-duped numeric list. */
function parseIds(params: URLSearchParams): number[] {
  const raw = params.getAll('signalIds').flatMap((v) => v.split(','));
  const out: number[] = [];
  for (const s of raw) {
    const n = Number(s.trim());
    // Integer-only: signal ids are int64 rows — "1.5" must not slip through to the backend.
    if (Number.isInteger(n) && n > 0 && !out.includes(n)) out.push(n);
  }
  return out;
}

const num = (v: number | null | undefined, dp = 2): string =>
  v == null || Number.isNaN(v) ? '—' : v.toLocaleString('en-IN', { maximumFractionDigits: dp });

/** The max finite value across the columns for a numeric accessor — the "best per row" (§8.5). */
function bestOf(columns: CompareColumn[], get: (c: CompareColumn) => number | null | undefined): number | null {
  let best: number | null = null;
  for (const c of columns) {
    const v = get(c);
    if (v != null && Number.isFinite(v) && (best == null || v > best)) best = v;
  }
  return best;
}

/** A metric cell; `best` bolds + tints it (default text on a light accent tint — WCAG-safe). */
function Cell({ children, best, className }: { children: React.ReactNode; best?: boolean; className?: string }) {
  return (
    <td
      className={cn(
        'whitespace-nowrap px-3 py-1.5 text-right tabular-nums text-ay-text',
        best && 'bg-accent/10 font-semibold',
        className,
      )}
    >
      {children}
    </td>
  );
}

function CompareMatrix({ data }: { data: CompareResult }) {
  const columns = useMemo(() => data.columns ?? [], [data.columns]);

  // The union of component keys across every column, in first-seen order (a NO_INSIGHT column is empty).
  const componentKeys = useMemo(() => {
    const seen: string[] = [];
    for (const c of columns) {
      for (const cp of c.components ?? []) {
        if (cp.key && !seen.includes(cp.key)) seen.push(cp.key);
      }
    }
    return seen;
  }, [columns]);

  const pointsFor = (c: CompareColumn, key: string): number | null =>
    (c.components ?? []).find((cp) => cp.key === key)?.points ?? null;

  const bestPriority = bestOf(columns, (c) => c.priority);
  const bestRR = bestOf(columns, (c) => c.riskReward);

  return (
    <BeatBlock>
      {/* What-differs banner, pinned on top (§8.5). */}
      <div className="mb-3 flex flex-col gap-1 rounded-md border border-ay-border bg-surface-2/40 px-3 py-2 text-body-sm">
        <span className="text-ay-text">
          {data.differsMost ? (
            <>
              These setups differ mainly on{' '}
              <span className="font-semibold text-accent">{data.differsMost}</span>.
            </>
          ) : (
            'Comparable priority profiles across the set.'
          )}
        </span>
        <span className="text-caption text-ay-muted">
          Session {data.session ?? '—'} · {columns.length} signals
          {data.booksDiffer && ' · mixed books (compare cost/R:R with care)'}
        </span>
      </div>

      <div
        className="overflow-x-auto rounded-md border border-ay-border"
        style={{ contain: 'paint' }}
        tabIndex={0}
        role="region"
        aria-label="Signal comparison matrix"
      >
        <table className="w-full text-sm">
          <thead className="bg-surface-1 text-left text-xs text-ay-muted">
            <tr>
              <th scope="col" className="sticky left-0 z-10 bg-surface-1 px-3 py-2 font-medium">Metric</th>
              {columns.map((c) => (
                <th key={c.signalId} scope="col" className="px-3 py-2 text-right font-medium">
                  <Link to={`/charts?symbol=${c.tradingsymbol ?? ''}`} className="text-ay-text hover:text-accent">
                    {c.tradingsymbol ?? `#${c.signalId}`}
                  </Link>
                  <div className="flex items-center justify-end gap-1.5">
                    <span className={cn('font-semibold', c.side === 'BUY' ? 'text-bull' : c.side === 'SELL' ? 'text-bear' : '')}>
                      {c.side ?? ''}
                    </span>
                    <BandChip band={(c.band as 'A' | 'B' | 'C' | 'D') ?? null} />
                  </div>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            <MetricRow label="Trust">
              {columns.map((c) => (
                <td key={c.signalId} className="px-3 py-1.5 text-right">
                  <TrustChip state={(c.dataTrust as InsightTrust) ?? 'OK'} reasons={c.trustReasons} />
                </td>
              ))}
            </MetricRow>

            <MetricRow label="Priority">
              {columns.map((c) => (
                <Cell key={c.signalId} best={c.priority != null && c.priority === bestPriority}>
                  {c.scored === 'NO_INSIGHT' ? (
                    <span className="text-ay-muted">no insight</span>
                  ) : c.scored === 'BLOCKED' || c.priority == null ? (
                    <span className="text-ay-muted">unscored</span>
                  ) : (
                    num(c.priority, 1)
                  )}
                </Cell>
              ))}
            </MetricRow>

            {componentKeys.map((key) => {
              const bestPts = bestOf(columns, (c) => pointsFor(c, key));
              const isDiffMost = key === data.differsMost;
              return (
                <MetricRow key={key} label={key} highlight={isDiffMost}>
                  {columns.map((c) => {
                    const pts = pointsFor(c, key);
                    return (
                      <Cell key={c.signalId} best={pts != null && pts === bestPts}>
                        {num(pts, 1)}
                      </Cell>
                    );
                  })}
                </MetricRow>
              );
            })}

            <MetricRow label="Option leg cost">
              {columns.map((c) => (
                <Cell key={c.signalId}>{c.optionLegCost == null ? '—' : `₹${num(c.optionLegCost)}`}</Cell>
              ))}
            </MetricRow>

            <MetricRow label="Margin">
              {columns.map((c) => (
                <Cell key={c.signalId}>
                  <span className="text-ay-muted" title="SPAN margin isn't priced here — request it on the paper ticket.">
                    {c.marginEstimate ?? 'unpriced'}
                  </span>
                </Cell>
              ))}
            </MetricRow>

            <MetricRow label="R:R">
              {columns.map((c) => (
                <Cell key={c.signalId} best={c.riskReward != null && c.riskReward === bestRR}>
                  {c.riskReward == null ? '—' : `${num(c.riskReward)}×`}
                </Cell>
              ))}
            </MetricRow>

            <MetricRow label="Entry">
              {columns.map((c) => (
                <Cell key={c.signalId}>{num(c.entryPrice)}</Cell>
              ))}
            </MetricRow>
            <MetricRow label="Stop">
              {columns.map((c) => (
                <Cell key={c.signalId}>{num(c.stopLoss)}</Cell>
              ))}
            </MetricRow>
            <MetricRow label="Target">
              {columns.map((c) => (
                <Cell key={c.signalId}>{num(c.target)}</Cell>
              ))}
            </MetricRow>
          </tbody>
        </table>
      </div>

      {data.notes && data.notes.length > 0 && (
        <ul className="mt-2 flex flex-col gap-0.5 text-caption text-ay-muted">
          {data.notes.map((n, i) => (
            <li key={i}>{n}</li>
          ))}
        </ul>
      )}
    </BeatBlock>
  );
}

/** A metric row with a sticky left label; `highlight` marks the max-spread ("differs most") component. */
function MetricRow({
  label,
  children,
  highlight,
}: {
  label: string;
  children: React.ReactNode;
  highlight?: boolean;
}) {
  return (
    <tr className="border-t border-ay-border/60">
      <th
        scope="row"
        className={cn(
          'sticky left-0 z-10 bg-surface-1 px-3 py-1.5 text-left text-xs font-medium text-ay-muted',
          highlight && 'text-accent',
        )}
      >
        {label}
        {highlight && <span className="ml-1 text-[10px] uppercase">differs most</span>}
      </th>
      {children}
    </tr>
  );
}

export function InsightsComparePage() {
  const [params] = useSearchParams();
  const ids = useMemo(() => parseIds(params), [params]);
  const q = useCompare(ids);

  const enough = ids.length >= 2 && ids.length <= 6;

  return (
    <LoadBeat>
      <PageHeader
        title="Compare signals"
        subtitle="Decide between 2–6 same-session setups — priority components, cost, R:R and data trust side by side"
        help="Pick 2 to 6 signals from the same trading session (select them on the Insights feed and click Compare, or open this page with ?signalIds=…). Each signal is a column; the priority components, option-leg cost, R:R and data-trust chips are the rows, with the best value in each numeric row highlighted and the component they differ on most pinned at the top. Margin is shown honestly as ‘unpriced’ — request the SPAN estimate on the paper ticket. Read-only; take or dismiss from the feed."
      />

      {!enough ? (
        <div
          role="note"
          className="flex flex-col items-center gap-2 rounded-lg border border-ay-border bg-surface-1 px-4 py-10 text-center"
        >
          <GitCompareArrows aria-hidden="true" className="size-6 text-ay-muted" />
          <p className="text-body font-medium text-ay-text">Select 2–6 signals to compare</p>
          <p className="text-caption text-ay-muted">
            On the{' '}
            <Link to="/insights" className="text-accent hover:underline">
              Insights feed
            </Link>
            , tick 2–6 signal rows and press “Compare selected”. This page is URL-addressable via{' '}
            <code className="text-ay-muted">?signalIds=1,2,3</code>.
          </p>
          {ids.length > 6 && (
            <p className="text-caption text-bear" role="alert">
              {ids.length} selected — the compare cap is 6.
            </p>
          )}
        </div>
      ) : q.isError && q.error instanceof ApiError ? (
        // Surface the comparability guard reason (different sessions / wrong count, §4.1) rather than a
        // generic error — the 422 D8 envelope carries a human message the owner needs to fix the pick.
        <div
          role="alert"
          className="flex flex-col items-center gap-3 rounded-lg border border-bear/40 bg-surface-1 px-4 py-8 text-center"
        >
          <AlertTriangle aria-hidden="true" className="size-6 text-bear" />
          <p className="text-body font-medium text-ay-text">These signals can't be compared</p>
          <p className="text-caption text-ay-muted">{q.error.message}</p>
          <Link to="/insights" className="text-caption text-accent hover:underline">
            Back to the feed →
          </Link>
        </div>
      ) : (
        <QueryState
          query={q}
          isEmpty={(d) => (d.columns?.length ?? 0) === 0}
          empty={{ icon: GitCompareArrows, title: 'No comparable signals for that selection.' }}
          errorTitle="Couldn't compare these signals"
          skeleton={<Skeleton variant="table-rows" />}
        >
          {(data) => <CompareMatrix data={data} />}
        </QueryState>
      )}
    </LoadBeat>
  );
}

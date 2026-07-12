import { useMemo } from 'react';
import { useActiveStrikes } from '../../api/oiAnalytics.ts';
import { foldActiveStrikeSeries } from '../../api/activeStrikesFold.ts';
import { Activity } from 'lucide-react';
import { FilterBar } from '../../components/FilterBar.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { FreshnessBadge } from '../../components/atoms/FreshnessBadge.tsx';
import {
  ActiveStrikeOiChart,
  ActiveStrikeSentimentChart,
} from '../../components/ActiveStrikeCharts.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { formatDecimal } from '../../lib/decimal.ts';
import type { OiInterval } from '../../stores/symbolContext.store.ts';

// Active Strikes OI (oipulse §options/active-strikes) — the server-picked active strike's Call/Put OI
// and Sentiment % through the session. Two charts off ONE /active-strikes?buckets=N call (one DB read):
// LEFT = Call OI (green) + Put OI (red) lines; RIGHT = Sentiment % (blue) line. The header KPIs (latest
// sentiment %, active-strike list, asOf) come off the same response.
//
// Faithful divergences (vs oipulse): we surface an Expiry picker (our endpoint keys on expiry; oipulse
// hides it and lets the server pick across the chain); the "active strike" is the aggregate of the top-N
// peak-OI strikes (ArthaYantra D4 default 5), not a single peak strike; sentiment uses the flow-based
// convention already shipped for the sentiment series. See the manual-test doc.

const ACTIVE_INTERVALS: readonly OiInterval[] = ['3m', '5m', '10m', '15m', '30m', '60m']; // oipulse: no 1m
const BUCKETS = 130; // ~a full session of 3-min buckets; the BE spans the last N from the newest bucket

export function ActiveStrikesPage() {
  const q = useActiveStrikes(BUCKETS);
  const data = q.data ?? null;

  const series = useMemo(
    () => foldActiveStrikeSeries(data?.activeStrikeOiSeries, data?.sentimentSeries),
    [data],
  );
  const hasSeries = series.times.length > 0;

  const activeStrikeLabels = useMemo(
    () => (data?.items ?? []).map((s) => s.strike).join(', '),
    [data],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="Active Strikes OI"
        help="Tracks the most active strike's Call and Put open interest plus the sentiment % through the session — rising Call OI leans bearish, rising Put OI leans bullish."
        right={<FreshnessBadge freshness={data?.freshness} />}
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval allowedIntervals={ACTIVE_INTERVALS} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <BeatStrip className="card shadow-e1 mb-4 flex flex-wrap items-center gap-2" aria-live="polite">
        <BeatItem>
          <Metric
            label="Sentiment %"
            value={data?.sentimentPct ? formatDecimal(data.sentimentPct, 2) : '—'}
          />
        </BeatItem>
        <BeatItem>
          <Metric label="Active strikes" value={activeStrikeLabels || '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
        </BeatItem>
      </BeatStrip>

      <QueryState
        query={q}
        isEmpty={() => !hasSeries}
        empty={{
          icon: Activity,
          title: 'No active-strike series — pick an index + expiry with captured chain snapshots.',
        }}
        errorTitle="Couldn't load active strikes OI"
        skeleton={
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-2">
            <Skeleton variant="chart-block" height={320} />
            <Skeleton variant="chart-block" height={320} />
          </div>
        }
      >
        {() => (
          <BeatBlock className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-2">
            <section className="card shadow-e1">
              <h2 className="mb-2 text-h3 text-ay-text">Active Strike Change in OI</h2>
              <ActiveStrikeOiChart data={series} />
            </section>
            <section className="card shadow-e1">
              <h2 className="mb-2 text-h3 text-ay-text">Active Strike Sentiment %</h2>
              <ActiveStrikeSentimentChart data={series} />
            </section>
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

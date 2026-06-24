import { useMemo } from 'react';
import { useActiveStrikes } from '../../api/oiAnalytics.ts';
import { foldActiveStrikeIvSeries } from '../../api/activeStrikesFold.ts';
import { Activity } from 'lucide-react';
import { FilterBar } from '../../components/FilterBar.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { ActiveStrikeIvChart } from '../../components/ActiveStrikeCharts.tsx';
import { formatDecimal, subtractDecimal } from '../../lib/decimal.ts';
import type { OiInterval } from '../../stores/symbolContext.store.ts';

// Active Strikes IV (oipulse §options/active-strikes-iv) — the active strike's Call IV / Put IV vs price
// through the session. IV counterpart of Active Strikes OI: rides the SAME /active-strikes?buckets=N call
// (one DB read), reading the activeStrikeIvSeries series. IV is per-strike + unsummable, so the BE picks
// the SINGLE peak-OI strike per bucket (vs the OI page's top-N aggregate) — a documented divergence.
//
// "Magic of IV" header: the CE−PE IV spread (a ~10-point gap → buy the higher-IV side in the trade
// direction). Other divergences: Expiry picker shown (endpoint keys on expiry); NSE-indices-only is an
// oipulse caveat. See the manual-test doc.

const ACTIVE_INTERVALS: readonly OiInterval[] = ['3m', '5m', '10m', '15m', '30m', '60m']; // oipulse: no 1m
const BUCKETS = 130; // ~a full session of 3-min buckets; BE spans the last N from the newest bucket

export function ActiveStrikesIvPage() {
  const q = useActiveStrikes(BUCKETS);
  const data = q.data ?? null;

  const series = useMemo(
    () => foldActiveStrikeIvSeries(data?.activeStrikeIvSeries),
    [data],
  );
  const hasSeries = series.times.length > 0;

  // Latest captured CE/PE IV (newest-last) + the CE−PE skew, off the raw decimal strings.
  const latest = useMemo(() => {
    const pts = data?.activeStrikeIvSeries ?? [];
    return pts.length ? pts[pts.length - 1] : null;
  }, [data]);
  const skew =
    latest?.ceIv && latest?.peIv ? subtractDecimal(latest.peIv, latest.ceIv) : null;

  return (
    <div>
      <PageHeader title="Active Strikes IV" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval allowedIntervals={ACTIVE_INTERVALS} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2" aria-live="polite">
        <Metric label="Call IV" value={latest?.ceIv ? formatDecimal(latest.ceIv, 2) : '—'} />
        <Metric label="Put IV" value={latest?.peIv ? formatDecimal(latest.peIv, 2) : '—'} />
        <Metric label="IV skew (P−C)" value={skew ? formatDecimal(skew, 2) : '—'} />
        <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => !hasSeries}
        empty={{
          icon: Activity,
          title: 'No active-strike IV series — pick an index + expiry with captured chain snapshots.',
        }}
        errorTitle="Couldn't load active strikes IV"
        skeleton={<Skeleton variant="chart-block" height={360} />}
      >
        {() => (
          <section className="card shadow-e1">
            <h2 className="mb-2 text-h3 text-ay-text">Active Strike IV</h2>
            <ActiveStrikeIvChart data={series} />
          </section>
        )}
      </QueryState>
    </div>
  );
}

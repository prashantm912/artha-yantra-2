import { useMemo } from 'react';
import { useActiveStrikes } from '../../api/oiAnalytics.ts';
import { foldActiveStrikeIvSeries, ivPct } from '../../api/activeStrikesFold.ts';
import { Activity } from 'lucide-react';
import { FilterBar } from '../../components/FilterBar.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { ActiveStrikeIvChart } from '../../components/ActiveStrikeCharts.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { subtractDecimal } from '../../lib/decimal.ts';
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

  // Per-side SPOT-solved IVs (§10.2-1): the stored IV is PCP-forward-solved, which forces
  // Call IV == Put IV (skew ≡ 0 by construction) — the side series makes the split visible.
  // Fallback to the stored series for a backend that predates the field.
  const points = data?.activeStrikeSideIvSeries ?? data?.activeStrikeIvSeries;
  const series = useMemo(() => foldActiveStrikeIvSeries(points), [points]);
  const hasSeries = series.times.length > 0;

  // Latest captured CE/PE IV (newest-last) + the P−C skew, in display percent.
  const latest = points?.length ? points[points.length - 1] : null;
  const skewDecimal =
    latest?.ceIv && latest?.peIv ? subtractDecimal(latest.peIv, latest.ceIv) : null;
  const skew = ivPct(skewDecimal);

  return (
    <LoadBeat>
      <PageHeader
        title="Active Strikes IV"
        help="Shows the most active strike's Call and Put implied volatility versus price through the session; the Call−Put IV gap tells you which side is richer."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval allowedIntervals={ACTIVE_INTERVALS} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <BeatStrip className="card shadow-e1 mb-4 flex flex-wrap items-center gap-2" aria-live="polite">
        <BeatItem>
          <Metric label="Call IV" value={latest?.ceIv ? `${ivPct(latest.ceIv)}%` : '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Put IV" value={latest?.peIv ? `${ivPct(latest.peIv)}%` : '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="IV skew (P−C)" value={skew ? `${skew}%` : '—'} />
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
          title: 'No active-strike IV series — pick an index + expiry with captured chain snapshots.',
        }}
        errorTitle="Couldn't load active strikes IV"
        skeleton={<Skeleton variant="chart-block" height={360} />}
      >
        {() => (
          <BeatBlock>
            <section className="card shadow-e1">
              <h2 className="mb-2 text-h3 text-ay-text">Active Strike IV</h2>
              <ActiveStrikeIvChart data={series} />
            </section>
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

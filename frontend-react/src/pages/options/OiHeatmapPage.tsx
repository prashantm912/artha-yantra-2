import { useOiHeatmap } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { CallOiHeatmap, PutOiHeatmap } from '../../components/OiHeatmapChart.tsx';
import type { OiInterval } from '../../stores/symbolContext.store.ts';

// OI Change Heatmap (§20 breadth — oipulse grid-heatmap archetype). A strike × time grid of the
// per-bucket interval OI change, split Call (CE) and Put (PE), for the ATM-window of strikes over the
// selected IST session. ONE /oi-heatmap read folds the day's snapshot buckets (zero new capture); the
// server centres on the ATM and returns maxAbs so both legs share a symmetric diverging colour scale.
// Reads where (which strike) and when (which interval) fresh OI is building — the spatial view the
// per-strike line charts and the snapshot table can't give. Lazy-loaded (bears the ECharts bundle).

const HEATMAP_INTERVALS: readonly OiInterval[] = ['3m', '5m', '10m', '15m', '30m', '60m']; // oipulse: no 1m

export function OiHeatmapPage() {
  const q = useOiHeatmap();
  const data = q.data ?? null;
  const hasGrid = !!data && data.buckets.length > 0 && data.strikes.length > 0;

  return (
    <div>
      <PageHeader title="OI Change Heatmap" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval allowedIntervals={HEATMAP_INTERVALS} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2" aria-live="polite">
        <Metric label="Strikes" value={hasGrid ? String(data!.strikes.length) : '—'} />
        <Metric label="Intervals" value={hasGrid ? String(data!.buckets.length) : '—'} />
        <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
      </div>

      {!hasGrid && !q.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No OI-change grid — pick an index + expiry with captured chain snapshots for the chosen day.
        </p>
      )}

      {hasGrid && (
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
          <section className="card shadow-e1">
            <h2 className="mb-2 text-h3 text-ay-text">Call (CE) OI Change</h2>
            <CallOiHeatmap data={data!} />
          </section>
          <section className="card shadow-e1">
            <h2 className="mb-2 text-h3 text-ay-text">Put (PE) OI Change</h2>
            <PutOiHeatmap data={data!} />
          </section>
        </div>
      )}
    </div>
  );
}

import { useEffect, useState } from 'react';
import { Grid3x3 } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useOiHeatmap } from '../../api/oiAnalytics.ts';
import { cn } from '../../lib/cn.ts';
import { optionsChartPath } from '../../lib/optionsLinks.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { CallOiHeatmap, PutOiHeatmap } from '../../components/OiHeatmapChart.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
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

  // Strike drill-down (audit §6.3): the heatmap is a canvas, so a keyboard-accessible strike picker +
  // link stands in for a per-cell link — pick a strike, jump to its Options Chart. Seeds to the ATM
  // (the server centres the grid on the ATM, so the middle strike is the closest listed strike).
  const [chartStrike, setChartStrike] = useState<string | null>(null);
  useEffect(() => {
    if (hasGrid && (!chartStrike || !data!.strikes.includes(chartStrike))) {
      setChartStrike(data!.strikes[Math.floor(data!.strikes.length / 2)]);
    }
  }, [hasGrid, data, chartStrike]);

  return (
    <LoadBeat>
      <PageHeader
        title="OI Change Heatmap"
        help="A strike-by-time grid showing where open interest is building or unwinding through the session — green cells mark fresh OI being added, red cells mark OI being closed."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval allowedIntervals={HEATMAP_INTERVALS} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
        {hasGrid && (
          <div className="flex items-center gap-1">
            <Select
              ariaLabel="Chart a strike"
              title="Open a strike's Call + Put premium chart"
              value={chartStrike}
              options={data!.strikes}
              onChange={setChartStrike}
              placeholder="Strike"
            />
            <Link
              to={optionsChartPath(chartStrike ?? '')}
              aria-disabled={!chartStrike}
              title="Open this strike in the Options Chart"
              className={cn(
                'inline-flex h-9 items-center rounded-md border border-ay-border bg-surface-2 px-3 text-sm text-accent hover:underline',
                !chartStrike && 'pointer-events-none opacity-50',
              )}
            >
              Chart
            </Link>
          </div>
        )}
      </div>

      <BeatStrip className="card shadow-e1 mb-4 flex flex-wrap items-center gap-2" aria-live="polite">
        <BeatItem>
          <Metric label="Strikes" value={hasGrid ? String(data!.strikes.length) : '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Intervals" value={hasGrid ? String(data!.buckets.length) : '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
        </BeatItem>
      </BeatStrip>

      <QueryState
        query={q}
        isEmpty={() => !hasGrid}
        empty={{
          icon: Grid3x3,
          title:
            'No OI-change grid — pick an index + expiry with captured chain snapshots for the chosen day.',
        }}
        errorTitle="Couldn't load OI heatmap"
        skeleton={
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-2">
            <Skeleton variant="chart-block" className="h-64 sm:h-80 lg:h-[420px]" />
            <Skeleton variant="chart-block" className="h-64 sm:h-80 lg:h-[420px]" />
          </div>
        }
      >
        {() =>
          hasGrid && (
            <BeatBlock className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-2">
              <section className="card shadow-e1">
                <h2 className="mb-2 text-h3 text-ay-text">Call (CE) OI Change</h2>
                <CallOiHeatmap data={data!} />
              </section>
              <section className="card shadow-e1">
                <h2 className="mb-2 text-h3 text-ay-text">Put (PE) OI Change</h2>
                <PutOiHeatmap data={data!} />
              </section>
            </BeatBlock>
          )
        }
      </QueryState>
    </LoadBeat>
  );
}

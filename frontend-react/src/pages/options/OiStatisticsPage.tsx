import { useMemo, useState } from 'react';
import { PieChart } from 'lucide-react';
import { useOiAnalysis, useOiStats, usePcrSeries } from '../../api/oiAnalytics.ts';
import { foldIndividualOi, type PcrPricePoint } from '../../api/oiStatsFold.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import {
  CumulativeOiChart,
  IndividualOiChart,
  PcrPriceChart,
} from '../../components/OiStatsCharts.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Options OI Statistics (oipulse §options/oi-statistics) — the OI-distribution + PCR view. Three charts,
// all folded from feeds already on the wire (no new backend): Cumulative OI + Individual per-strike OI
// (from /oi-analysis latest-bucket rows) and the intraday PCR-vs-price line (from /trending). PCR + Max
// Pain in the header come from /oi-stats (server-side MaxPainCalculator — not re-derived here).
//
// Faithful divergences (vs oipulse): the "Select Period" (Full-day / last-N-min window) control maps to
// the shared Interval cadence here; the underlying DH/DL/DO quote strip is not surfaced (a known
// cross-page gap — oi-stats serves OI totals, not the underlying OHLC). See the manual-test doc.

const compact = (n: number) => new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(n);

export function OiStatisticsPage() {
  const statsQ = useOiStats();
  const analysisQ = useOiAnalysis();
  const pcrSeriesQ = usePcrSeries();
  const [showChange, setShowChange] = useState(false);

  const stats = statsQ.data ?? null;
  const points = useMemo(() => analysisQ.data ?? [], [analysisQ.data]);

  // Both bar charts fold the SAME per-strike points so absolute OI and ΔOI stay mutually consistent;
  // the cumulative totals are the column sums (oi-stats' ceOi/peOi equal these for the absolute view).
  const individual = useMemo(() => foldIndividualOi(points, showChange), [points, showChange]);
  const callTotal = useMemo(() => individual.ce.reduce((a, b) => a + b, 0), [individual]);
  const putTotal = useMemo(() => individual.pe.reduce((a, b) => a + b, 0), [individual]);

  // /pcr-series is source-aware (native fold or Upstox full-chain); map its decimal strings to the
  // dual-axis chart shape (PCR left, price right).
  const pcrPrice = useMemo<PcrPricePoint[]>(
    () =>
      (pcrSeriesQ.data ?? []).map((p) => ({
        time: p.time,
        pcr: p.pcr == null ? null : Number(p.pcr),
        price: p.spot == null ? null : Number(p.spot),
      })),
    [pcrSeriesQ.data],
  );

  const hasBars = individual.strikes.length > 0;

  return (
    <LoadBeat>
      <PageHeader title="Options OI Statistics" subtitle="OI distribution + PCR — cumulative & per-strike OI and the intraday PCR-vs-price line" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry />
        <label className="flex h-9 items-center gap-1.5 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text">
          <input
            type="checkbox"
            checked={showChange}
            onChange={(e) => setShowChange(e.target.checked)}
            className="accent-accent"
          />
          Show Chg. in OI
        </label>
        <GoButton
          onClick={() => {
            void statsQ.refetch();
            void analysisQ.refetch();
            void pcrSeriesQ.refetch();
          }}
          loading={statsQ.isFetching || analysisQ.isFetching || pcrSeriesQ.isFetching}
        />
      </div>

      <BeatStrip className="card shadow-e1 mb-4 flex flex-wrap items-center gap-2" aria-live="polite">
        <BeatItem>
          <Metric label="PCR" value={stats?.pcr ? formatDecimal(stats.pcr, 2) : '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Max Pain" value={stats?.maxPain ? formatDecimal(stats.maxPain, 0) : '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Total Call OI" value={stats ? compact(stats.ceOi) : '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Total Put OI" value={stats ? compact(stats.peOi) : '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Last updated" value={stats?.asOf ? stats.asOf.slice(11, 19) : '—'} />
        </BeatItem>
      </BeatStrip>

      <QueryState
        query={analysisQ}
        isEmpty={() => !hasBars}
        empty={{
          icon: PieChart,
          title: 'No OI snapshot — pick an underlying + expiry with captured chain snapshots.',
        }}
        errorTitle="Couldn't load OI statistics"
        skeleton={
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <Skeleton variant="chart-block" height={300} className="lg:col-span-1" />
            <Skeleton variant="chart-block" height={300} className="lg:col-span-2" />
          </div>
        }
      >
        {() => (
          <BeatBlock className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <section className="card shadow-e1 lg:col-span-1">
              <h2 className="mb-1 text-h3 text-ay-text">
                Cumulative OI{showChange ? ' (Chg.)' : ''}
              </h2>
              <CumulativeOiChart callOi={callTotal} putOi={putTotal} changeView={showChange} />
            </section>
            <section className="card shadow-e1 lg:col-span-2">
              <h2 className="mb-1 text-h3 text-ay-text">
                Individual OI{showChange ? ' (Chg.)' : ''} — Call (resistance) vs Put (support)
              </h2>
              <IndividualOiChart data={individual} changeView={showChange} />
            </section>
          </BeatBlock>
        )}
      </QueryState>

      {pcrPrice.length > 0 && (
        <section className="card shadow-e1 mt-4">
          <h2 className="mb-1 text-h3 text-ay-text">PCR vs Price</h2>
          <PcrPriceChart points={pcrPrice} />
        </section>
      )}
    </LoadBeat>
  );
}

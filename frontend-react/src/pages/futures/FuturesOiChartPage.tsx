import { useState } from 'react';
import { LineChart } from 'lucide-react';
import { useFuturesOiChart } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { Select, type SelectOption } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { FuturesOiChart } from '../../components/FuturesOiChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// Futures OI Chart (§futures/oi-chart). The dual-axis OI-vs-price combo for one index future: real price
// candlesticks (right axis) + the OI line (left axis). Keyed on the index name; the BE picks the active
// front contract. The interval set is RAW MINUTES owned by the page (oipulse's 1/3/5/10/15/30/60 — the
// 1-min option is this page's defining feature, served by aggregating the contract's 1m base bars).
// Revamp rollout: visible display-face H1 (was ay-sr-only — text preserved) + the chart wrapped in a
// carded surface (card shadow-e1). The chart itself already consumes --ay-chart-*/--ay-bull/--ay-bear
// via the shared EChart theme; aria/dataZoom stay verbatim. Symbol-gated → existing empty paths kept.

const INTERVAL_OPTIONS: SelectOption[] = [1, 3, 5, 10, 15, 30, 60].map((m) => ({
  value: String(m),
  label: `${m} min`,
}));

export function FuturesOiChartPage() {
  const [intervalMin, setIntervalMin] = useState(3);
  const q = useFuturesOiChart(intervalMin);
  const data = q.data ?? null;

  return (
    <LoadBeat>
      <PageHeader
        title="Futures OI chart"
        subtitle="Dual-axis OI-vs-price combo for the active front index future"
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry={false} showInterval={false} />
        <Select
          ariaLabel="Time interval"
          value={String(intervalMin)}
          options={INTERVAL_OPTIONS}
          onChange={(v) => setIntervalMin(parseInt(v, 10) || 3)}
        />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      {/* Contract header strip. */}
      <div className="card shadow-e1 mb-3" aria-live="polite">
        <BeatStrip className="flex flex-wrap items-center gap-2">
          <BeatItem>
            <Metric label="Contract" value={data?.tradingsymbol ?? '—'} />
          </BeatItem>
          <BeatItem>
            <Metric label="Expiry" value={data?.expiry ?? '—'} />
          </BeatItem>
          <BeatItem>
            <Metric label="Interval" value={data?.interval ?? '—'} />
          </BeatItem>
          <BeatItem>
            <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
          </BeatItem>
        </BeatStrip>
      </div>

      <QueryState
        query={q}
        skeleton={<Skeleton variant="chart-block" className="h-64 sm:h-80 lg:h-[440px]" />}
        empty={{
          icon: LineChart,
          title: 'No futures OI chart — pick an underlying with a listed future.',
        }}
      >
        {(d) =>
          d.items.length === 0 ? (
            <p className="mb-3 text-sm text-ay-muted">
              No intraday bars for this contract/session yet.
            </p>
          ) : (
            <BeatBlock className="card shadow-e1">
              <h2 className="mb-1 text-h3 font-semibold text-ay-text">
                Futures Oi Vs. Price Analysis
              </h2>
              <FuturesOiChart items={d.items} tradingsymbol={d.tradingsymbol} />
            </BeatBlock>
          )
        }
      </QueryState>
    </LoadBeat>
  );
}

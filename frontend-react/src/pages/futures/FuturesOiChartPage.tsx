import { useState } from 'react';
import { useFuturesOiChart } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { Select, type SelectOption } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { FuturesOiChart } from '../../components/FuturesOiChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';

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
    <div>
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
      <div className="mb-3 flex flex-wrap items-center gap-2" aria-live="polite">
        <Metric label="Contract" value={data?.tradingsymbol ?? '—'} />
        <Metric label="Expiry" value={data?.expiry ?? '—'} />
        <Metric label="Interval" value={data?.interval ?? '—'} />
        <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
      </div>

      {data == null && !q.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No futures OI chart — pick an underlying with a listed future.
        </p>
      )}
      {data != null && data.items.length === 0 && !q.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No intraday bars for this contract/session yet.
        </p>
      )}

      {data != null && data.items.length > 0 && (
        <div className="card shadow-e1">
          <h2 className="mb-1 text-center text-sm font-semibold text-ay-text">
            Futures Oi Vs. Price Analysis
          </h2>
          <FuturesOiChart items={data.items} tradingsymbol={data.tradingsymbol} />
        </div>
      )}
    </div>
  );
}

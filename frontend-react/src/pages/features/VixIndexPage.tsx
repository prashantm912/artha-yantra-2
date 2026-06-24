import { useMemo, useState } from 'react';
import { useVixIndex } from '../../api/vixIndex.ts';
import { foldVixIndex } from '../../core/vixIndexSeries.ts';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { VixIndexChart } from '../../components/VixIndexChart.tsx';

// Vix & Index (§features/vix-index — oipulse "Vix & Price Chart"). Two stacked dual-axis line charts:
// India VIX vs NIFTY 50 and India VIX vs NIFTY BANK, a per-minute series for the selected IST day. ZERO
// backend — three reuse /market/candles 1m reads (useVixIndex), union-by-minute fold (foldVixIndex), one
// VixIndexChart rendered twice. No instrument selector (oipulse fixes the three symbols); controls = a
// date picker + Go. Lazy-loaded (bears the ECharts bundle).

/** The IST calendar day as 'YYYY-MM-DD' (timezone-pinned, independent of the host TZ). */
function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
}

export function VixIndexPage() {
  const [date, setDate] = useState<string>(todayIst());
  const q = useVixIndex(date);
  const data = q.data;

  const niftyChart = useMemo(() => foldVixIndex(data?.vix, data?.nifty), [data]);
  const bankChart = useMemo(() => foldVixIndex(data?.vix, data?.bankNifty), [data]);
  const empty = niftyChart.xAxis.length === 0 && bankChart.xAxis.length === 0;
  const updatedAt = q.dataUpdatedAt ? new Date(q.dataUpdatedAt).toLocaleTimeString('en-IN') : '—';

  return (
    <LoadBeat>
      <PageHeader title="Vix & Index" subtitle="India VIX vs NIFTY 50 / NIFTY BANK — dual-axis intraday lines for the selected IST day" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <DateInput ariaLabel="Select date" value={date} onChange={(v) => setDate(v ?? todayIst())} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
        <Metric label="Data updated at" value={updatedAt} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => empty}
        empty={{
          title:
            'No minute data captured for this day — VIX & index 1m bars accrue from live capture; pick a forward-captured trading day.',
        }}
        errorTitle="Couldn't load VIX & index data"
        skeleton={<Skeleton variant="chart-block" height={360} />}
      >
        {() => (
          <>
            {niftyChart.xAxis.length > 0 && (
              <BeatBlock className="card shadow-e1">
                <h2 className="mb-1 text-h3 text-ay-text">India Vix Vs. Nifty</h2>
                <VixIndexChart series={niftyChart} priceLabel="Nifty" />
              </BeatBlock>
            )}

            {bankChart.xAxis.length > 0 && (
              <BeatBlock className="card shadow-e1 mt-4">
                <h2 className="mb-1 text-h3 text-ay-text">India Vix Vs. Banknifty</h2>
                <VixIndexChart series={bankChart} priceLabel="Banknifty" />
              </BeatBlock>
            )}
          </>
        )}
      </QueryState>
    </LoadBeat>
  );
}

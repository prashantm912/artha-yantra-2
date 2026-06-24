import { useMemo } from 'react';
import { useFuturesOiSeries } from '../../api/oiAnalytics.ts';
import { foldFuturesOiAnalysis, type FuturesOiAnalysisRow } from '../../api/futuresOiAnalysisFold.ts';
import { bucketWindow } from '../../api/oiAnalysisFold.ts';
import type { OiInterval } from '../../stores/symbolContext.store.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { OiBadge4 } from '../../components/atoms/OiBadge4.tsx';
import { SignedCount } from '../../components/atoms/SignedCount.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Futures OI Analysis (oipulse §futures/oi-analysis): the per-interval OI table for ONE futures contract
// — descending intervals, each row carrying Total OI / cum ΔOI / day H-L / level break / interval volume /
// LTP / ΔLTP / ΔOI / 4-state interpretation. Off the /oi-analysis-series feed (raw points; the FE folds
// every derived column). The BE picks the active front contract; level break uses the REAL captured day
// high/low (V015). The 15:30-EOD adjusted-OI row is intentionally absent (a separate NSE source). NOTE:
// our capture is forward-only — the series 422s until ≥2 buckets of the index future have accrued.

// oipulse's interval set is 3/5/10/15/30/60; our OiInterval lacks 10m, so we offer the supported subset.
const ANALYSIS_INTERVALS: readonly OiInterval[] = ['3m', '5m', '15m', '30m', '60m'];

const num = (n: number | null) => (n != null ? n.toLocaleString('en-IN') : '—');
const dec = (s: string | null, d = 2) => (s ? formatDecimal(s, d) : '—');

function BreakBadge({ row }: { row: FuturesOiAnalysisRow }) {
  // breakLevel = the new session extreme the bucket broke (oipulse shows that level).
  const level = row.breakLevel ? `(${formatDecimal(row.breakLevel, 2)}) ` : '';
  if (row.dhBreak) {
    return <span className="rounded bg-bull/15 px-1 text-xs text-bull">D.H.B {level}↑</span>;
  }
  if (row.dlBreak) {
    return <span className="rounded bg-bear/15 px-1 text-xs text-bear">D.L.B {level}↓</span>;
  }
  return null; // oipulse renders a blank cell when there is no break
}

export function FuturesOiAnalysisPage() {
  const q = useFuturesOiSeries();
  const rows = useMemo(() => foldFuturesOiAnalysis(q.data?.items ?? []), [q.data]);
  const intervalMinutes = useMemo(() => parseInt(q.data?.interval ?? '3m', 10) || 3, [q.data]);

  const columns: DataColumn<FuturesOiAnalysisRow>[] = [
    {
      id: 'time',
      header: 'Date Time',
      align: 'left',
      sortValue: (r) => r.bucket,
      sortType: 'text',
      render: (r) => bucketWindow(r.bucket, intervalMinutes),
      mobileLabel: 'Time',
    },
    { id: 'oi', header: 'Total OI', sortValue: (r) => r.totalOi, render: (r) => num(r.totalOi), mobileLabel: 'Total OI' },
    {
      id: 'cumOi',
      header: 'Total Chng. In OI',
      sortValue: (r) => r.totalChngInOi,
      render: (r) => <SignedCount value={r.totalChngInOi} />,
      mobileLabel: 'Chng OI',
    },
    { id: 'high', header: 'Day High', sortValue: (r) => r.dayHigh, sortType: 'decimal', render: (r) => dec(r.dayHigh) },
    { id: 'low', header: 'Day Low', sortValue: (r) => r.dayLow, sortType: 'decimal', render: (r) => dec(r.dayLow) },
    { id: 'break', header: 'Level Break', align: 'center', render: (r) => <BreakBadge row={r} /> },
    { id: 'vol', header: 'Volume', sortValue: (r) => r.volume, render: (r) => num(r.volume), mobileLabel: 'Vol' },
    { id: 'ltp', header: 'LTP', sortValue: (r) => r.ltp, sortType: 'decimal', render: (r) => dec(r.ltp), mobileLabel: 'LTP' },
    {
      id: 'ltpChg',
      header: 'LTP Change',
      render: (r) => <ValueDeltaCell value={r.ltpChange} />,
      mobileLabel: 'LTP Chg',
    },
    {
      id: 'oiChg',
      header: 'OI Change',
      sortValue: (r) => r.oiChange,
      render: (r) => <SignedCount value={r.oiChange} />,
      mobileLabel: 'OI Chg',
    },
    {
      id: 'interp',
      header: 'OI Interpretation',
      align: 'center',
      render: (r) => <OiBadge4 value={r.interpretation} full />,
      mobileLabel: 'OI Int',
    },
  ];

  return (
    <div>
      <PageHeader
        title="Futures OI Analysis"
        subtitle="Per-interval OI, level breaks and 4-state interpretation for one futures contract"
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry={false} showInterval allowedIntervals={ANALYSIS_INTERVALS} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => rows.length === 0}
        empty={{ title: 'No intraday futures data for this contract yet.' }}
        errorTitle="Couldn't load futures OI analysis"
        skeleton={<Skeleton variant="table-rows" rows={10} cols={11} />}
      >
        {() => (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(r) => r.bucket}
            pageSize={25}
            initialSort={{ id: 'time', dir: 'desc' }}
            ariaLabel="Futures OI analysis"
            emptyMessage="No intraday futures data for this contract yet."
          />
        )}
      </QueryState>
    </div>
  );
}

import { useMemo } from 'react';
import { useTrendingOi } from '../../api/oiAnalytics.ts';
import { foldTrending, type TrendingRow } from '../../api/trendingOiFold.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { SignedCount } from '../../components/atoms/SignedCount.tsx';
import { SentimentBadge } from '../../components/atoms/SentimentBadge.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// OI Trending (oipulse §options/trending-oi): aggregated Call vs Put OI over the session with a
// derived directional sentiment. Time-ordered (newest on top), 100/page. All Δ/PCR/sentiment columns
// are FE-folded (foldTrending) from the per-bucket OI series; the column set + Diff-in-OI sign
// (puts−calls, positive = Bullish) follow the study doc.

const TRENDING_INTERVALS = ['3m', '5m', '10m', '15m', '30m', '60m'] as const; // oipulse: no 1m

function BreakCell({ row }: { row: TrendingRow }) {
  const level = row.breakLevel ? `(${formatDecimal(row.breakLevel, 2)}) ` : '';
  if (row.dhBreak) return <span className="rounded bg-bull/15 px-1 text-xs text-bull">D.H.B {level}↑</span>;
  if (row.dlBreak) return <span className="rounded bg-bear/15 px-1 text-xs text-bear">D.L.B {level}↓</span>;
  return null; // blank cell (no break) — matches oipulse
}

function Direction({ dir }: { dir: number }) {
  if (dir === 0) return <span className="text-ay-muted">—</span>;
  const up = dir > 0;
  return (
    <span className={up ? 'text-bull' : 'text-bear'}>
      <span aria-hidden="true">{up ? '↑' : '↓'}</span>
      <span className="ay-sr-only">{up ? 'up' : 'down'}</span>
    </span>
  );
}

export function TrendingOiPage() {
  const q = useTrendingOi();
  // The fold is oldest-first; oipulse reads newest-on-top.
  const rows = useMemo(() => foldTrending(q.data?.items ?? []).reverse(), [q.data]);

  const columns: DataColumn<TrendingRow>[] = [
    { id: 'date', header: 'Date', align: 'left', render: (r) => r.bucket.slice(0, 10) },
    { id: 'time', header: 'Time', align: 'left', render: (r) => r.bucket.slice(11, 16), mobileLabel: 'Time' },
    { id: 'ltp', header: 'LTP', render: (r) => (r.spot ? formatDecimal(r.spot, 2) : '—'), mobileLabel: 'LTP' },
    { id: 'break', header: 'Day H/L Break', align: 'center', render: (r) => <BreakCell row={r} /> },
    { id: 'chngCall', header: 'Chng. In Call OI', render: (r) => <SignedCount value={r.chngCallOi} />, mobileLabel: 'Δ Call OI' },
    { id: 'chngPut', header: 'Chng. In Put OI', render: (r) => <SignedCount value={r.chngPutOi} />, mobileLabel: 'Δ Put OI' },
    { id: 'diff', header: 'Diff. in OI', render: (r) => <SignedCount value={r.diffInOi} />, mobileLabel: 'Diff OI' },
    { id: 'direction', header: 'Direction of chng.', align: 'center', render: (r) => <Direction dir={r.direction} /> },
    { id: 'chngDir', header: 'Chng. In Direction', render: (r) => <SignedCount value={r.chngInDirection} /> },
    { id: 'dirPct', header: 'Direction of chng. %', render: (r) => <ValueDeltaCell value={r.directionPct} suffix="%" /> },
    { id: 'pcr', header: 'Net PCR', render: (r) => r.netPcr ?? '—', mobileLabel: 'PCR' },
    {
      id: 'sentiment',
      header: 'Sentiment',
      align: 'center',
      render: (r) => <SentimentBadge label={r.sentiment.label} tone={r.sentiment.tone} />,
      mobileLabel: 'Sentiment',
    },
  ];

  return (
    <div>
      <h1 className="ay-sr-only">OI Trending</h1>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval allowedIntervals={TRENDING_INTERVALS} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <p className="mb-3 text-xs text-ay-muted">
        Diff. in OI = ΔPut OI − ΔCall OI (puts−calls); positive = Bullish. Δ columns are cumulative vs
        the session-open baseline.
      </p>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(r) => r.bucket}
        pageSize={100}
        ariaLabel="OI trending per interval"
        emptyMessage="No trending data — pick an underlying + expiry with captured snapshots."
      />
    </div>
  );
}

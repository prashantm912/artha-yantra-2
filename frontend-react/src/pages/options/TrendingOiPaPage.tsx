import { useMemo } from 'react';
import { useTrendingOi } from '../../api/oiAnalytics.ts';
import { foldTrending, type TrendingRow } from '../../api/trendingOiFold.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { SignedCount } from '../../components/atoms/SignedCount.tsx';
import { SentimentBadge } from '../../components/atoms/SentimentBadge.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Trending OI - PA (oipulse §options/trending-oi-pa): the Trending OI table PLUS price-action columns —
// the summed Call/Put premium (Total Call/Put Ltp) and its deltas (Call/Put Ltp chng + the CE+PE straddle
// change). Same /trending feed (extended BE with ceLtp/peLtp sums) + the same foldTrending; this page just
// renders the extra premium columns so a trader sees whether OI shifts are confirmed by premium moves.

const TRENDING_INTERVALS = ['3m', '5m', '10m', '15m', '30m', '60m'] as const; // oipulse: no 1m

const dec = (s: string | null) => (s ? formatDecimal(s, 2) : '—');

function BreakCell({ row }: { row: TrendingRow }) {
  const level = row.breakLevel ? `(${formatDecimal(row.breakLevel, 2)}) ` : '';
  if (row.dhBreak) return <span className="rounded bg-bull/15 px-1 text-xs text-bull">D.H.B {level}↑</span>;
  if (row.dlBreak) return <span className="rounded bg-bear/15 px-1 text-xs text-bear">D.L.B {level}↓</span>;
  return null;
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

export function TrendingOiPaPage() {
  const q = useTrendingOi();
  const rows = useMemo(() => foldTrending(q.data?.items ?? []).reverse(), [q.data]); // newest on top

  const columns: DataColumn<TrendingRow>[] = [
    { id: 'date', header: 'Date', align: 'left', render: (r) => r.bucket.slice(0, 10) },
    { id: 'time', header: 'Time', align: 'left', render: (r) => r.bucket.slice(11, 16), mobileLabel: 'Time' },
    { id: 'ltp', header: 'LTP', render: (r) => (r.spot ? formatDecimal(r.spot, 2) : '—'), mobileLabel: 'LTP' },
    { id: 'break', header: 'Day H/L Break', align: 'center', render: (r) => <BreakCell row={r} /> },
    { id: 'chngCall', header: 'Chng. In Call OI', render: (r) => <SignedCount value={r.chngCallOi} />, mobileLabel: 'Δ Call OI' },
    { id: 'chngPut', header: 'Chng. In Put OI', render: (r) => <SignedCount value={r.chngPutOi} />, mobileLabel: 'Δ Put OI' },
    { id: 'diff', header: 'Diff. in OI', render: (r) => <SignedCount value={r.diffInOi} /> },
    { id: 'direction', header: 'Direction of chng.', align: 'center', render: (r) => <Direction dir={r.direction} /> },
    { id: 'chngDir', header: 'Chng. In Direction', render: (r) => <SignedCount value={r.chngInDirection} /> },
    // ── Price-action (premium) columns ──
    { id: 'totCall', header: 'Total Call Ltp', render: (r) => dec(r.totalCallLtp), mobileLabel: 'Call Ltp' },
    { id: 'callChng', header: 'Call Ltp chng.', render: (r) => <ValueDeltaCell value={r.callLtpChng} /> },
    { id: 'straddle', header: 'CE + PE Ltp Chng.', render: (r) => <ValueDeltaCell value={r.straddleChng} />, mobileLabel: 'CE+PE Δ' },
    { id: 'putChng', header: 'Put Ltp chng.', render: (r) => <ValueDeltaCell value={r.putLtpChng} /> },
    { id: 'totPut', header: 'Total Put Ltp', render: (r) => dec(r.totalPutLtp), mobileLabel: 'Put Ltp' },
    // ──
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
    <LoadBeat>
      <PageHeader title="Trending OI - PA" subtitle="Trending OI plus price-action columns — premium sums confirm OI shifts" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval allowedIntervals={TRENDING_INTERVALS} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <p className="mb-3 text-xs text-ay-muted">
        Price-action columns: Total Call/Put Ltp = summed premium across the chain; Δ columns are vs the
        session-open baseline. CE+PE Ltp Chng (the straddle change) confirms an OI shift when both move together.
      </p>

      <QueryState
        query={q}
        isEmpty={() => rows.length === 0}
        empty={{ title: 'No trending data — pick an underlying + expiry with captured snapshots.' }}
        errorTitle="Couldn't load trending OI"
        skeleton={<Skeleton variant="table-rows" rows={8} cols={16} />}
      >
        {() => (
          <BeatBlock>
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(r) => r.bucket}
              pageSize={100}
              ariaLabel="Trending OI with price action per interval"
              emptyMessage="No trending data — pick an underlying + expiry with captured snapshots."
            />
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

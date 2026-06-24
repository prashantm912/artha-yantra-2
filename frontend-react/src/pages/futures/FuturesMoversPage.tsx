import { useMemo, useState } from 'react';
import { useFuturesMovers } from '../../api/oiAnalytics.ts';
import type { MoverRow } from '../../api/types.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { OiBadge4 } from '../../components/atoms/OiBadge4.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { cn } from '../../lib/cn.ts';
import { compareDecimal, formatDecimal } from '../../lib/decimal.ts';

// Futures Market Movers (oipulse §futures/market-movers): Top Gainers | Top Losers by day price%, with
// the O=H/L open-positioning flag (from the surfaced day OHLC) + OI interpretation. The "Min. B.O.
// Days" breakout column is DROPPED — it needs a multi-day prior-high/low history we don't yet capture
// (documented). Universe = index + 17 bank-sector futures (reduced vs oipulse's all-F&O scan).
// Revamp rollout: visible display-face H1 (was ay-sr-only — text preserved) + QueryState over the
// gainers/losers region. Search filters client-side, so empty stays per-table.

/** Intraday open-positioning: O=L (open=low, bullish) / O=H (open=high, bearish) / —. */
function openFlag(r: MoverRow): { text: string; tone: string } {
  if (r.dayOpen && r.dayLow && compareDecimal(r.dayOpen, r.dayLow) === 0) return { text: 'O=L', tone: 'text-bull' };
  if (r.dayOpen && r.dayHigh && compareDecimal(r.dayOpen, r.dayHigh) === 0) return { text: 'O=H', tone: 'text-bear' };
  return { text: '—', tone: 'text-ay-muted' };
}

const COLUMNS: DataColumn<MoverRow>[] = [
  { id: 'name', header: 'Name', align: 'left', sortValue: (r) => r.tradingsymbol, sortType: 'text', render: (r) => r.tradingsymbol, mobileLabel: 'Name' },
  { id: 'ltp', header: 'LTP', sortValue: (r) => r.ltp, sortType: 'decimal', render: (r) => (r.ltp ? formatDecimal(r.ltp, 2) : '—'), mobileLabel: 'LTP' },
  { id: 'ltpPct', header: 'LTP Chg %', sortValue: (r) => r.pricePct, sortType: 'decimal', render: (r) => <ValueDeltaCell value={r.pricePct} suffix="%" />, mobileLabel: 'LTP %' },
  { id: 'oiPct', header: 'OI Chg %', sortValue: (r) => r.oiPct, sortType: 'decimal', render: (r) => <ValueDeltaCell value={r.oiPct} suffix="%" />, mobileLabel: 'OI %' },
  {
    id: 'ohl',
    header: 'O=H/L',
    align: 'center',
    render: (r) => {
      const f = openFlag(r);
      return <span className={cn('font-semibold', f.tone)}>{f.text}</span>;
    },
  },
  { id: 'interp', header: 'Oi Int.', align: 'center', render: (r) => <OiBadge4 value={r.interpretation} />, mobileLabel: 'OI Int' },
];

export function FuturesMoversPage() {
  const q = useFuturesMovers();
  const [search, setSearch] = useState('');

  const needle = search.trim().toUpperCase();
  const filterRows = (rows: MoverRow[]) =>
    needle ? rows.filter((r) => r.tradingsymbol.toUpperCase().includes(needle)) : rows;
  const gainers = useMemo(
    () => filterRows(q.data?.gainers ?? []),
    // filterRows closes over `needle`, which is in the deps; q.data is the other input.
    [q.data, needle], // eslint-disable-line react-hooks/exhaustive-deps
  );
  const losers = useMemo(
    () => filterRows(q.data?.losers ?? []),
    [q.data, needle], // eslint-disable-line react-hooks/exhaustive-deps
  );

  return (
    <LoadBeat>
      <PageHeader
        title="Futures Market Movers"
        subtitle="Top gainers and losers by day price %, with OI interpretation"
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry={false} showInterval />
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search symbol"
          aria-label="Search symbol"
          className="h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => (q.data?.gainers.length ?? 0) === 0 && (q.data?.losers.length ?? 0) === 0}
        empty={{ title: 'No futures movers for this selection.' }}
        errorTitle="Couldn't load futures movers"
        skeleton={
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <Skeleton variant="table-rows" rows={8} cols={6} />
            <Skeleton variant="table-rows" rows={8} cols={6} />
          </div>
        }
      >
        {() => (
          <BeatBlock className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <div>
              <h2 className="mb-1 text-h3 font-semibold text-bull">Top Gainers</h2>
              <DataTable columns={COLUMNS} rows={gainers} rowKey={(r) => r.tradingsymbol} pageSize={8} initialSort={{ id: 'ltpPct', dir: 'desc' }} ariaLabel="Top gainers" emptyMessage="No gainers." />
            </div>
            <div>
              <h2 className="mb-1 text-h3 font-semibold text-bear">Top Losers</h2>
              <DataTable columns={COLUMNS} rows={losers} rowKey={(r) => r.tradingsymbol} pageSize={8} initialSort={{ id: 'ltpPct', dir: 'asc' }} ariaLabel="Top losers" emptyMessage="No losers." />
            </div>
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

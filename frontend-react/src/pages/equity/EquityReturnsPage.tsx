import { useMemo, useState } from 'react';
import { useEquityReturns } from '../../api/oiAnalytics.ts';
import type { EquityReturnRow } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { EodBadge } from '../../components/atoms/EodBadge.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { formatDecimal } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';

// Equity → Equity Returns (oipulse): a multi-timeframe returns screener over the EQ universe — LTP +
// % return over Current Day / 1 Week / 1 Month / 6 Months / 1 Year, with the stock's sector. Restricted
// to the sector-mapped (curated) universe. NSE bhavcopy history forward-accrues, so the longer windows
// (6M / 1Y) stay blank until enough sessions accrue. Default sort: Current Day desc.

const num = (s: string | null): number => (s == null ? 0 : Number(s));

const RET_COLS: { id: string; header: string; help: string; pick: (r: EquityReturnRow) => string | null }[] = [
  { id: 'r1d', header: 'Current Day', help: "Price return so far today versus the previous session's close.", pick: (r) => r.r1d },
  { id: 'r1w', header: '1 Week', help: 'Price return over the past week.', pick: (r) => r.r1w },
  { id: 'r1m', header: '1 Month', help: 'Price return over the past month.', pick: (r) => r.r1m },
  { id: 'r6m', header: '6 Months', help: 'Price return over the past six months (blank until enough history accrues).', pick: (r) => r.r6m },
  { id: 'r1y', header: '1 Year', help: 'Price return over the past year (blank until enough history accrues).', pick: (r) => r.r1y },
];

export function EquityReturnsPage() {
  const q = useEquityReturns();
  const [search, setSearch] = useState('');

  const rows = useMemo(() => {
    const all = q.data?.items ?? [];
    const needle = search.trim().toUpperCase();
    const filtered = needle
      ? all.filter(
          (r) =>
            r.symbol.toUpperCase().includes(needle) ||
            (r.industry ?? '').toUpperCase().includes(needle),
        )
      : all;
    // Default view: biggest Current-Day gainers first (nulls last).
    return [...filtered].sort((a, b) => num(b.r1d) - num(a.r1d));
  }, [q.data, search]);

  const columns: DataColumn<EquityReturnRow>[] = useMemo(
    () => [
      { id: 'symbol', header: 'Name', help: 'The stock ticker.', align: 'left', render: (r) => r.symbol, sortValue: (r) => r.symbol, mobileLabel: 'Name' },
      { id: 'industry', header: 'Industry', help: 'The sector / industry the stock is mapped to.', align: 'left', render: (r) => r.industry ?? '—', sortValue: (r) => r.industry ?? '', mobileLabel: 'Industry' },
      { id: 'ltp', header: 'LTP', help: FIELD_HELP.ltp, render: (r) => (r.ltp ? formatDecimal(r.ltp, 2) : '—'), sortValue: (r) => num(r.ltp), mobileLabel: 'LTP' },
      ...RET_COLS.map(
        (c): DataColumn<EquityReturnRow> => ({
          id: c.id,
          header: c.header,
          help: c.help,
          render: (r) => <ValueDeltaCell value={c.pick(r)} suffix="%" />,
          sortValue: (r) => num(c.pick(r)),
          mobileLabel: c.header,
        }),
      ),
    ],
    [],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="Equity Returns"
        help="A screener ranking stocks by their price return across several timeframes (today through 1 year) so you can spot momentum leaders and laggards at a glance."
        subtitle={
          <>
            Multi-timeframe % returns over the EQ universe · 6M / 1Y fill in as history accrues{' '}
            {q.data?.asOf ? <EodBadge asOf={q.data.asOf} /> : null}
          </>
        }
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search name or sector…"
          aria-label="Search name or sector"
          title="Filter the rows by stock name or sector"
          className="h-9 w-full sm:w-56 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <GoButton onClick={() => void q.refetch()} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={(d) => (d.items?.length ?? 0) === 0}
        empty={{ title: 'No returns data yet.' }}
        errorTitle="Couldn't load equity returns"
        skeleton={<Skeleton variant="table-rows" rows={10} cols={8} />}
      >
        {() => (
          <BeatBlock>
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(r) => r.symbol}
              pageSize={50}
              ariaLabel="Equity returns screener"
              emptyMessage="No returns data yet."
            />
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

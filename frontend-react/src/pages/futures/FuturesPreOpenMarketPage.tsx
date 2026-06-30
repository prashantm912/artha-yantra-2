import { useMemo, useState } from 'react';
import { Sunrise } from 'lucide-react';
import { useFuturesPreOpen } from '../../api/futuresPreOpen.ts';
import type { FuturesPreOpen, FuturesPreOpenRow } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { cn } from '../../lib/cn.ts';
import { compareDecimal, formatDecimal } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';

// Futures Pre-Open Market (oipulse §futures/pre-open-market): the 09:00–09:08 pre-market scan. A 2×2 grid
// — radar stock futures (Advances | Declines) on top, indices (Advances | Declines) below — split client-
// side by the sign of the net change, plus the market advance/decline/unchanged counters. Each row shows
// the pre-open price, prev close, net change %/abs, and a Prev-Day High/Low Break badge. Prices are
// decimal STRINGS (formatted exact, never parseFloat); the Break badge text + the sign-aware ValueDeltaCell
// carry the meaning (never colour alone, a11y). Live pre-open data is published ~09:09 IST — the page shows
// that note; outside the window it lists the last/closing quotes. v1 radar = the captured NIFTY-Bank
// stock-future set (prev-day H/L from the captured EOD); the full NIFTY-50 widening is the v2 Upstox step.

/** Prev-day break badge: green "High Break" / red "Low Break" / muted dash — the text carries the cue. */
function BreakBadge({ value }: { value: 'H' | 'L' | null }) {
  if (value == null) return <span className="text-ay-muted">—</span>;
  const high = value === 'H';
  return (
    <span
      className={cn(
        'inline-flex items-center rounded px-1.5 py-0.5 text-caption font-semibold',
        high ? 'bg-bull/15 text-bull' : 'bg-bear/15 text-bear',
      )}
    >
      {high ? 'High Break' : 'Low Break'}
    </span>
  );
}

const COLUMNS: DataColumn<FuturesPreOpenRow>[] = [
  {
    id: 'name',
    header: 'Name',
    align: 'left',
    pin: 'left',
    sortValue: (r) => r.symbol,
    sortType: 'text',
    render: (r) => <span className="font-medium text-ay-text">{r.symbol}</span>,
    mobileLabel: 'Name',
    help: 'The F&O stock future / index this pre-open row shows.',
  },
  {
    id: 'open',
    header: 'Today Open',
    align: 'right',
    sortValue: (r) => r.preOpenPrice,
    sortType: 'decimal',
    render: (r) =>
      r.preOpenPrice == null ? (
        <span className="text-ay-muted">—</span>
      ) : (
        <span className="nums">{formatDecimal(r.preOpenPrice, 2)}</span>
      ),
    mobileLabel: 'Open',
    help: 'The pre-open indicative price (the exchange pre-open session close).',
  },
  {
    id: 'prevClose',
    header: 'Prev Close',
    align: 'right',
    sortValue: (r) => r.prevClose,
    sortType: 'decimal',
    render: (r) =>
      r.prevClose == null ? (
        <span className="text-ay-muted">—</span>
      ) : (
        <span className="nums text-ay-muted">{formatDecimal(r.prevClose, 2)}</span>
      ),
    mobileLabel: 'Prev Close',
    help: FIELD_HELP.prevClose,
  },
  {
    id: 'chgPct',
    header: 'LTP Chg %',
    align: 'right',
    sortValue: (r) => r.changePct,
    sortType: 'decimal',
    render: (r) => <ValueDeltaCell value={r.changePct} suffix="%" />,
    mobileLabel: 'Chg %',
    help: FIELD_HELP.changePct,
  },
  {
    id: 'chg',
    header: 'LTP Chg',
    align: 'right',
    sortValue: (r) => r.change,
    sortType: 'decimal',
    render: (r) => <ValueDeltaCell value={r.change} />,
    mobileLabel: 'Chg',
    help: 'Net change of the pre-open price vs the previous close.',
  },
  {
    id: 'break',
    header: 'Prev Day Break',
    align: 'center',
    render: (r) => <BreakBadge value={r.prevDayBreak} />,
    mobileLabel: 'Break',
    help: "High Break = pre-open price above yesterday's high; Low Break = below yesterday's low.",
  },
];

/** One Advances / Declines sub-table. */
function PreOpenTable({
  title,
  tone,
  rows,
  sortDir,
}: {
  title: string;
  tone: string;
  rows: FuturesPreOpenRow[];
  sortDir: 'asc' | 'desc';
}) {
  return (
    <div>
      <h2 className={cn('mb-1 text-h3 font-semibold', tone)}>
        {title} <span className="nums text-caption font-normal text-ay-muted">({rows.length})</span>
      </h2>
      <DataTable
        columns={COLUMNS}
        rows={rows}
        rowKey={(r) => r.symbol}
        pageSize={7}
        initialSort={{ id: 'chgPct', dir: sortDir }}
        ariaLabel={title}
        emptyMessage={`No ${title.toLowerCase()}.`}
      />
    </div>
  );
}

/** Advancing = change > 0, declining = change < 0; price-less / zero rows fall out of both tables. */
function advancing(r: FuturesPreOpenRow): boolean {
  return r.change != null && compareDecimal(r.change, '0') > 0;
}
function declining(r: FuturesPreOpenRow): boolean {
  return r.change != null && compareDecimal(r.change, '0') < 0;
}

export function FuturesPreOpenMarketPage() {
  const q = useFuturesPreOpen();
  const [search, setSearch] = useState('');
  const data: FuturesPreOpen | undefined = q.data;

  const needle = search.trim().toUpperCase();
  const split = useMemo(() => {
    const filter = (rows: FuturesPreOpenRow[]) =>
      needle ? rows.filter((r) => r.symbol.toUpperCase().includes(needle)) : rows;
    const stocks = filter(data?.stocks ?? []);
    const indices = filter(data?.indices ?? []);
    return {
      stockAdv: stocks.filter(advancing),
      stockDec: stocks.filter(declining),
      idxAdv: indices.filter(advancing),
      idxDec: indices.filter(declining),
    };
  }, [data, needle]);

  return (
    <LoadBeat>
      <PageHeader
        title="Futures Pre-Open Market"
        subtitle="The 09:00–09:08 pre-market scan — which F&O stock futures and indices advance / decline vs prev close, and break the prior day's high/low"
        help="The pre-open session snapshot: radar stock futures and indices split into Advances and Declines by their net change vs the previous close, with a Prev-Day High/Low Break badge. Live pre-open data is published by the exchange around 09:09 IST; outside that window the last/closing quotes are shown."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search symbol"
          aria-label="Search symbol"
          title="Filter the tables to symbols containing this text"
          className="h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
        {data && (
          <span className="ml-auto flex items-center gap-3 text-caption font-medium">
            <span className="text-bull">Advances {data.advances}</span>
            <span className="text-bear">Declines {data.declines}</span>
            <span className="text-ay-muted">Unchanged {data.unchanged}</span>
          </span>
        )}
      </div>

      <p className="mb-3 text-caption text-ay-muted">
        Note: on a live trading day the pre-open market data updates here on or after 09:09:30 IST.
      </p>

      <QueryState
        query={q}
        isEmpty={(d) => d.stocks.length === 0 && d.indices.length === 0}
        empty={{
          icon: Sunrise,
          title: 'No pre-open scan available.',
          hint: 'The Upstox pre-open feed is not configured on this stack, or no pre-open snapshot has been published yet.',
        }}
        errorTitle="Couldn't load the futures pre-open scan"
        skeleton={
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <Skeleton variant="table-rows" rows={7} cols={6} />
            <Skeleton variant="table-rows" rows={7} cols={6} />
            <Skeleton variant="table-rows" rows={5} cols={6} />
            <Skeleton variant="table-rows" rows={5} cols={6} />
          </div>
        }
      >
        {() => (
          <BeatBlock className="flex flex-col gap-5">
            <section aria-label="Pre-open market (stock futures)">
              <h2 className="mb-2 text-h2 text-ay-text">Pre-Open Market (Stock Futures)</h2>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                <PreOpenTable title="Advances" tone="text-bull" rows={split.stockAdv} sortDir="desc" />
                <PreOpenTable title="Declines" tone="text-bear" rows={split.stockDec} sortDir="asc" />
              </div>
            </section>
            <section aria-label="Pre-open indices">
              <h2 className="mb-2 text-h2 text-ay-text">Pre-Open Indices</h2>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                <PreOpenTable title="Indice Advances" tone="text-bull" rows={split.idxAdv} sortDir="desc" />
                <PreOpenTable title="Indice Declines" tone="text-bear" rows={split.idxDec} sortDir="asc" />
              </div>
            </section>
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

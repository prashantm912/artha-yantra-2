import { useMemo } from 'react';
import { Globe, TrendingDown, TrendingUp } from 'lucide-react';
import { useWorldIndices } from '../../api/worldIndices.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { WorldIndicesMap } from '../../components/WorldIndicesMap.tsx';
import { PageHeader, LiveDot } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { cn } from '../../lib/cn.ts';
import { compareDecimal, formatDecimal, isNegative } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';
import type { WorldIndex } from '../../api/types.ts';

// World Indices (§features/world-indices). A sortable, read-only table of Upstox global indices (+ a
// commodities GLOBAL_INDICATOR section, same wire): Name · Country · LTP · Net Change · %Chg · As Of.
// Net change + %change are sign-aware — the sign + an explicit arrow carry the meaning (never colour
// alone, a11y #2); the --ay-bull/--ay-bear tones are supportive. LTP is plain mono (nums). Prices arrive
// as decimal STRINGS and are formatted exact (never parseFloat). Rows whose live quote did not resolve
// list with a muted dash. Default-sorted by %change descending (today's strongest first).

/** The HH:mm:ss IST clock from the snapshot's ISO +05:30 asOf, for the live-dot detail. */
function asOfClock(iso: string | undefined): string | undefined {
  if (!iso) return undefined;
  const t = iso.match(/T(\d{2}:\d{2}:\d{2})/);
  return t ? `${t[1]} IST` : undefined;
}

/** Signed change cell: sign + arrow + --ay-bull/--ay-bear tone (text cue first), muted dash when null. */
function ChangeCell({ value, suffix = '' }: { value: string | null; suffix?: string }) {
  if (value == null) return <span className="text-ay-muted">—</span>;
  const zero = compareDecimal(value, '0') === 0;
  const neg = isNegative(value);
  const tone = zero ? '' : neg ? 'text-bear' : 'text-bull';
  const Arrow = zero ? null : neg ? TrendingDown : TrendingUp;
  const sign = !neg && !zero ? '+' : '';
  return (
    <span className={cn('nums inline-flex items-center justify-end gap-0.5', tone)}>
      {Arrow && <Arrow aria-hidden="true" className="size-3.5" />}
      {sign}
      {formatDecimal(value, 2)}
      {suffix}
    </span>
  );
}

export function WorldIndicesPage() {
  const q = useWorldIndices();
  const rows = q.data ?? [];
  const asOf = rows.find((r) => r.asOf)?.asOf;

  const columns = useMemo<DataColumn<WorldIndex>[]>(
    () => [
      {
        id: 'name',
        header: 'Index',
        align: 'left',
        pin: 'left',
        help: 'The global stock-market index (e.g. S&P 500, Nikkei 225, FTSE 100).',
        sortValue: (r) => r.name,
        sortType: 'text',
        render: (r) => (
          <span className="font-medium text-ay-text">
            {r.name}
            {r.tradingSymbol && <span className="ml-1 text-ay-muted">{r.tradingSymbol}</span>}
          </span>
        ),
        mobileLabel: 'Index',
      },
      {
        id: 'country',
        header: 'Country',
        align: 'left',
        help: 'The country the index belongs to.',
        sortValue: (r) => r.country ?? '',
        sortType: 'text',
        render: (r) => r.country ?? '—',
        mobileLabel: 'Country',
      },
      {
        id: 'ltp',
        header: 'LTP',
        align: 'right',
        help: FIELD_HELP.ltp,
        sortValue: (r) => r.ltp,
        sortType: 'decimal',
        render: (r) => (r.ltp == null ? <span className="text-ay-muted">—</span> : <span className="nums">{formatDecimal(r.ltp, 2)}</span>),
        mobileLabel: 'LTP',
      },
      {
        id: 'netChange',
        header: 'Net Chg',
        align: 'right',
        help: FIELD_HELP.netChange,
        sortValue: (r) => r.netChange,
        sortType: 'decimal',
        render: (r) => <ChangeCell value={r.netChange} />,
        mobileLabel: 'Net Chg',
      },
      {
        id: 'changePct',
        header: '% Chg',
        align: 'right',
        help: FIELD_HELP.changePct,
        sortValue: (r) => r.changePct,
        sortType: 'decimal',
        render: (r) => <ChangeCell value={r.changePct} suffix="%" />,
        mobileLabel: '% Chg',
      },
      {
        id: 'asOf',
        header: 'As Of',
        align: 'right',
        help: 'The timestamp of the latest quote for this index (global feeds carry a publication delay).',
        sortValue: (r) => r.asOf,
        sortType: 'text',
        render: (r) => <span className="nums text-ay-muted">{asOfClock(r.asOf) ?? '—'}</span>,
        mobileLabel: 'As Of',
      },
    ],
    [],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="World Indices"
        subtitle="Global index live quotes from Upstox — net change and %change are sign-aware (green up, red down)"
        help="Live quotes for the major global stock indices. The map plots each index at its home country (green up, red down, bubble size by move); the table below has the exact numbers. Global feeds carry a publication delay, so quotes lag the live market."
        right={<LiveDot detail={asOfClock(asOf)} />}
      />

      <QueryState
        query={q}
        empty={{ icon: Globe, title: 'No world-index quotes available.', hint: 'The Upstox global feed is not configured on this stack.' }}
        errorTitle="Couldn't load world indices"
        skeleton={<Skeleton variant="table-rows" rows={12} cols={6} />}
      >
        {() => (
          <div className="space-y-3">
            <BeatBlock className="card shadow-e1">
              <WorldIndicesMap rows={rows} />
            </BeatBlock>
            <BeatBlock>
              <DataTable
                columns={columns}
                rows={rows}
                rowKey={(r) => r.key}
                pageSize={50}
                initialSort={{ id: 'changePct', dir: 'desc' }}
                emptyMessage="No world-index quotes available."
                ariaLabel="World indices live quotes"
              />
            </BeatBlock>
          </div>
        )}
      </QueryState>
    </LoadBeat>
  );
}

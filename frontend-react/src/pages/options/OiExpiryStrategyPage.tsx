import { useEffect, useMemo, useState } from 'react';
import { CalendarRange } from 'lucide-react';
import { useOiExpiry } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { OiBadge4 } from '../../components/atoms/OiBadge4.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { formatDecimal } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';
import type { OiExpiryEodDay } from '../../api/types.ts';

// OI Expiry Strategy — oipulse "Options EOD OI Analysis" (§options/oi-expiry-strategy). Per-strike,
// last-N-session EOD OHLC + OI + Volume tables (one CE table + one PE table for the chosen strike),
// with day-over-day % change in close / OI, the 4-state OI interpretation, and the all-day-high/low
// flag (★) — the data input to the expiry-day trading plan. One /oi-expiry read folds the day-rollup
// over the captured snapshots (zero new capture); the server windows to the ATM strikes and returns
// one `{items}` entry per strike. NOTE: capture is forward-only (history accrues from boot), so the
// window is shallow until sessions accrue.

const num = (n: number | null) => (n == null ? '—' : n.toLocaleString('en-IN'));
const dec = (s: string | null) => (s ? formatDecimal(s, 2) : '—');

/** Columns for one leg's daily EOD table (shared by the CE and PE tables). */
const columns: DataColumn<OiExpiryEodDay>[] = [
  { id: 'date', header: 'Date', align: 'left', sortValue: (r) => r.date, sortType: 'text', render: (r) => r.date, mobileLabel: 'Date', help: 'The trading session this row summarises.' },
  {
    id: 'open',
    header: 'Open',
    render: (r) => dec(r.open),
    help: FIELD_HELP.open,
  },
  {
    id: 'high',
    header: 'High',
    render: (r) => (
      <span>
        {dec(r.high)}
        {r.allDayHigh && <span title="window high" aria-label="window high"> ★</span>}
      </span>
    ),
    help: 'Session high; a ★ marks the highest high across the captured window.',
  },
  {
    id: 'low',
    header: 'Low',
    render: (r) => (
      <span>
        {dec(r.low)}
        {r.allDayLow && <span title="window low" aria-label="window low"> ★</span>}
      </span>
    ),
    help: 'Session low; a ★ marks the lowest low across the captured window.',
  },
  { id: 'close', header: 'Close', render: (r) => dec(r.close), mobileLabel: 'Close', help: FIELD_HELP.close },
  { id: 'vol', header: 'Volume', sortValue: (r) => r.volume, render: (r) => num(r.volume), mobileLabel: 'Volume', help: FIELD_HELP.volume },
  { id: 'closePct', header: '% Chg Close', render: (r) => <ValueDeltaCell value={r.changeInClosePct} suffix="%" />, mobileLabel: '% Close', help: 'Percentage change in the close versus the previous session.' },
  { id: 'oiPct', header: '% Chg OI', render: (r) => <ValueDeltaCell value={r.changeInOiPct} suffix="%" />, mobileLabel: '% OI', help: FIELD_HELP.oiChangePct },
  { id: 'oi', header: 'OI', sortValue: (r) => r.oi, render: (r) => num(r.oi), mobileLabel: 'OI', help: FIELD_HELP.oi },
  { id: 'interp', header: 'OI Interpretation', align: 'center', render: (r) => <OiBadge4 value={r.interpretation} full />, mobileLabel: 'OI Int', help: FIELD_HELP.oiInterpretation },
];

function LegTable({ leg, rows }: { leg: string; rows: OiExpiryEodDay[] }) {
  return (
    <section>
      <h2 className="mb-1 text-h3 text-ay-text">{leg}</h2>
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(r) => r.date}
        pageSize={15}
        ariaLabel={`${leg} EOD OI table`}
        emptyMessage="No captured sessions for this leg yet."
      />
    </section>
  );
}

export function OiExpiryStrategyPage() {
  const q = useOiExpiry();
  const strikes = useMemo(() => (q.data ?? []).map((s) => s.strike), [q.data]);

  // Default to the middle strike of the returned ATM window (the server centres on the ATM).
  const [strike, setStrike] = useState<string | null>(null);
  useEffect(() => {
    if (strikes.length && (!strike || !strikes.includes(strike))) {
      setStrike(strikes[Math.floor(strikes.length / 2)]);
    }
  }, [strikes, strike]);

  const selected = useMemo(
    () => (q.data ?? []).find((s) => s.strike === strike) ?? null,
    [q.data, strike],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="OI Expiry Strategy"
        help="Per-strike end-of-day OHLC, volume and open-interest history with day-over-day changes and the OI interpretation — the data behind an expiry-day trading plan."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval />
        {strikes.length > 0 && (
          <Select ariaLabel="Strike" title="Strike whose Call and Put EOD tables to show" value={strike} options={strikes} onChange={setStrike} />
        )}
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => !selected}
        empty={{
          icon: CalendarRange,
          title:
            'No EOD OI history — pick an index + expiry with captured chain snapshots. The window accrues from boot, so it is shallow until sessions build up.',
        }}
        errorTitle="Couldn't load OI expiry strategy"
        skeleton={
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-2">
            <Skeleton variant="table-rows" rows={10} cols={10} />
            <Skeleton variant="table-rows" rows={10} cols={10} />
          </div>
        }
      >
        {() =>
          selected && (
            <BeatBlock className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-2">
              <LegTable leg={`${selected.strike} CE`} rows={selected.ce} />
              <LegTable leg={`${selected.strike} PE`} rows={selected.pe} />
            </BeatBlock>
          )
        }
      </QueryState>
    </LoadBeat>
  );
}

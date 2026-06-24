import { useMemo } from 'react';
import { useChainTable, useOptionsSpurt } from '../../api/oiAnalytics.ts';
import type { SpurtRow } from '../../api/types.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { MoneynessBadge, type Moneyness } from '../../components/atoms/MoneynessBadge.tsx';
import { OiBadge4 } from '../../components/atoms/OiBadge4.tsx';
import { SignedCount } from '../../components/atoms/SignedCount.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { compareDecimal, formatDecimal } from '../../lib/decimal.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// Big OI Movement (oipulse §options/big-oi-movement): the biggest OI-change strikes, split CE | PE,
// tagged by moneyness + OI interpretation. The faithful columns need per-leg ΔLTP + interpretation
// (ΔLTP×ΔOI), which the /spurt feed already carries — so this reuses /spurt (top-N by |ΔOI| per side)
// + /chain-table for the underlying spot (Asset Price + Moneyness). oipulse's "big" threshold is
// server-side; we surface the top N by |ΔOI| per side.

const TOP_N = 10;

function moneyness(strike: string, spot: string | null, atm: string | null, type: 'CE' | 'PE'): Moneyness {
  if (atm && compareDecimal(strike, atm) === 0) return 'ATM';
  if (!spot) return 'ATM';
  const cmp = compareDecimal(strike, spot); // strike vs spot
  if (type === 'CE') return cmp < 0 ? 'ITM' : 'OTM';
  return cmp > 0 ? 'ITM' : 'OTM';
}

function topBySide(rows: SpurtRow[], side: 'CE' | 'PE'): SpurtRow[] {
  return rows
    .filter((r) => r.optionType === side)
    .sort((a, b) => Math.abs(b.oiChange) - Math.abs(a.oiChange))
    .slice(0, TOP_N);
}

export function BigOiMovementPage() {
  const spurtQ = useOptionsSpurt();
  const chainQ = useChainTable();

  const spot = chainQ.data?.spot ?? null;
  const asOf = spurtQ.data?.asOf ?? null;
  const items = useMemo(() => spurtQ.data?.items ?? [], [spurtQ.data]);
  const atm = useMemo(
    () => nearestStrike([...new Set(items.map((r) => r.strike))], spot),
    [items, spot],
  );

  const ce = useMemo(() => topBySide(items, 'CE'), [items]);
  const pe = useMemo(() => topBySide(items, 'PE'), [items]);
  const time = asOf ? asOf.slice(11, 16) : '—';

  const columns = (side: 'CE' | 'PE'): DataColumn<SpurtRow>[] => [
    { id: 'time', header: 'Time', align: 'left', render: () => time, mobileLabel: 'Time' },
    { id: 'asset', header: 'Asset Price', render: () => (spot ? formatDecimal(spot, 2) : '—'), mobileLabel: 'Asset' },
    { id: 'strike', header: 'Strike Price', render: (r) => <span className="font-semibold">{r.strike}</span>, mobileLabel: 'Strike' },
    {
      id: 'moneyness',
      header: 'Moneyness',
      align: 'center',
      render: (r) => <MoneynessBadge value={moneyness(r.strike, spot, atm, side)} />,
      mobileLabel: 'Moneyness',
    },
    { id: 'close', header: 'Close Price', render: (r) => (r.ltp ? formatDecimal(r.ltp, 2) : '—'), mobileLabel: 'Close' },
    { id: 'ltpChg', header: 'LTP Chg.', render: (r) => <ValueDeltaCell value={r.ltpChange} />, mobileLabel: 'LTP Chg' },
    { id: 'oiChg', header: 'OI Chg.', render: (r) => <SignedCount value={r.oiChange} />, mobileLabel: 'OI Chg' },
    { id: 'interp', header: 'OI Interpretation', align: 'center', render: (r) => <OiBadge4 value={r.interpretation} full />, mobileLabel: 'OI Int' },
  ];

  return (
    <LoadBeat>
      <PageHeader title="Big OI Movement" subtitle="Biggest OI-change strikes, split CE | PE, tagged by moneyness + OI interpretation" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval />
        <GoButton onClick={() => { spurtQ.refetch(); chainQ.refetch(); }} loading={spurtQ.isFetching} />
      </div>

      <BeatStrip className="card shadow-e1 mb-3 flex flex-wrap items-center gap-2 text-sm text-ay-muted">
        {spot && (
          <BeatItem>
            <Metric label="Spot" value={formatDecimal(spot, 2)} />
          </BeatItem>
        )}
        <span className="text-xs">· top {TOP_N} OI moves per side · as of {time}</span>
      </BeatStrip>

      <QueryState
        query={spurtQ}
        isEmpty={() => items.length === 0}
        empty={{ title: 'No OI moves — pick an underlying + expiry with captured snapshots.' }}
        errorTitle="Couldn't load OI movement"
        skeleton={
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-2">
            <Skeleton variant="table-rows" rows={10} cols={8} />
            <Skeleton variant="table-rows" rows={10} cols={8} />
          </div>
        }
      >
        {() => (
          <BeatBlock className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-2">
            <div>
              <div className="mb-1 text-sm font-semibold text-bear">CALL (CE)</div>
              <DataTable columns={columns('CE')} rows={ce} rowKey={(r) => r.strike} ariaLabel="Big OI movement — calls" emptyMessage="No CE moves." />
            </div>
            <div>
              <div className="mb-1 text-sm font-semibold text-bull">PUT (PE)</div>
              <DataTable columns={columns('PE')} rows={pe} rowKey={(r) => r.strike} ariaLabel="Big OI movement — puts" emptyMessage="No PE moves." />
            </div>
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

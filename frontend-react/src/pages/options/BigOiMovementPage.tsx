import { useMemo, useState } from 'react';
import { useBigOiLog, useChainTable, useOptionsSpurt } from '../../api/oiAnalytics.ts';
import type { BigOiLogEvent, SpurtRow } from '../../api/types.ts';
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
import { cn } from '../../lib/cn.ts';
import { compareDecimal, formatDecimal } from '../../lib/decimal.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { FIELD_HELP } from '../../core/fieldHelp.ts';

// Big OI Movement (oipulse §options/big-oi-movement): the biggest OI-change strikes, split CE | PE,
// tagged by moneyness + OI interpretation. The faithful columns need per-leg ΔLTP + interpretation
// (ΔLTP×ΔOI), which the /spurt feed already carries — so this reuses /spurt (top-N by |ΔOI| per side)
// + /chain-table for the underlying spot (Asset Price + Moneyness). oipulse's "big" threshold is
// server-side; we surface the top N by |ΔOI| per side. The "Session log" view (audit §9.2-5) is the
// barometer's chronological read — every interval's biggest moves timestamped through the day.

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
  const [view, setView] = useState<'latest' | 'log'>('latest');
  const spurtQ = useOptionsSpurt();
  const chainQ = useChainTable();
  const logQ = useBigOiLog(view === 'log');

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
    { id: 'time', header: 'Time', align: 'left', help: 'Clock time of this OI-movement snapshot.', render: () => time, mobileLabel: 'Time' },
    { id: 'asset', header: 'Asset Price', help: FIELD_HELP.spot, render: () => (spot ? formatDecimal(spot, 2) : '—'), mobileLabel: 'Asset' },
    { id: 'strike', header: 'Strike Price', help: FIELD_HELP.strike, render: (r) => <span className="font-semibold">{r.strike}</span>, mobileLabel: 'Strike' },
    {
      id: 'moneyness',
      header: 'Moneyness',
      align: 'center',
      help: FIELD_HELP.moneyness,
      render: (r) => <MoneynessBadge value={moneyness(r.strike, spot, atm, side)} />,
      mobileLabel: 'Moneyness',
    },
    { id: 'close', header: 'Close Price', help: 'Latest traded price (LTP) of this option contract.', render: (r) => (r.ltp ? formatDecimal(r.ltp, 2) : '—'), mobileLabel: 'Close' },
    { id: 'ltpChg', header: 'LTP Chg.', help: "Change in the option's premium versus its baseline reading.", render: (r) => <ValueDeltaCell value={r.ltpChange} />, mobileLabel: 'LTP Chg' },
    { id: 'oiChg', header: 'OI Chg.', help: FIELD_HELP.oiChange, render: (r) => <SignedCount value={r.oiChange} />, mobileLabel: 'OI Chg' },
    { id: 'interp', header: 'OI Interpretation', align: 'center', help: FIELD_HELP.oiInterpretation, render: (r) => <OiBadge4 value={r.interpretation} full />, mobileLabel: 'OI Int' },
  ];

  return (
    <LoadBeat>
      <PageHeader title="Big OI Movement" help="Lists the strikes with the largest OI changes — either the latest interval's top-10 leaderboard split calls/puts, or the Session log: every interval's biggest moves timestamped through the day." subtitle="Latest interval's top-10 leaderboard, or the chronological session event log" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval />
        <div className="inline-flex rounded-md border border-ay-border" role="group" aria-label="Leaderboard or session log">
          <button
            type="button"
            onClick={() => setView('latest')}
            aria-pressed={view === 'latest'}
            title="Top-10 OI moves of the LATEST interval, calls and puts side by side."
            className={cn(
              'h-9 rounded-l-md px-3 text-sm',
              view === 'latest' ? 'bg-accent/15 font-semibold text-accent' : 'text-ay-muted hover:text-ay-text',
            )}
          >
            Latest top-10
          </button>
          <button
            type="button"
            onClick={() => setView('log')}
            aria-pressed={view === 'log'}
            title="Chronological event log: each interval's biggest OI moves through the whole session."
            className={cn(
              'h-9 rounded-r-md border-l border-ay-border px-3 text-sm',
              view === 'log' ? 'bg-accent/15 font-semibold text-accent' : 'text-ay-muted hover:text-ay-text',
            )}
          >
            Session log
          </button>
        </div>
        <GoButton
          onClick={() => { spurtQ.refetch(); chainQ.refetch(); if (view === 'log') logQ.refetch(); }}
          loading={spurtQ.isFetching || logQ.isFetching}
        />
      </div>

      <BeatStrip className="card shadow-e1 mb-3 flex flex-wrap items-center gap-2 text-sm text-ay-muted">
        {spot && (
          <BeatItem>
            <Metric label="Spot" value={formatDecimal(spot, 2)} />
          </BeatItem>
        )}
        <span className="text-xs">
          {view === 'latest'
            ? `· top ${TOP_N} OI moves per side · as of ${time}`
            : `· biggest moves per interval, newest first · as of ${logQ.data?.asOf ? logQ.data.asOf.slice(11, 16) : time}`}
        </span>
      </BeatStrip>

      {view === 'latest' ? (
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
      ) : (
        <QueryState
          query={logQ}
          isEmpty={() => (logQ.data?.items?.length ?? 0) === 0}
          empty={{ title: 'No logged OI moves yet — events accrue as intervals complete.' }}
          errorTitle="Couldn't load the OI session log"
          skeleton={<Skeleton variant="table-rows" rows={12} cols={9} />}
        >
          {() => (
            <BeatBlock>
              <DataTable
                columns={logColumns(atm)}
                rows={logQ.data?.items ?? []}
                rowKey={(e) => `${e.time}-${e.strike}-${e.optionType}`}
                ariaLabel="Big OI movement — session event log"
                emptyMessage="No logged moves."
              />
            </BeatBlock>
          )}
        </QueryState>
      )}
    </LoadBeat>
  );
}

/** The Session-log columns: one chronological row per big interval move (newest first). */
function logColumns(atm: string | null): DataColumn<BigOiLogEvent>[] {
  return [
    { id: 'time', header: 'Time', align: 'left', help: 'IST end of the interval this move happened in.', render: (e) => e.time, mobileLabel: 'Time' },
    { id: 'asset', header: 'Asset Price', help: FIELD_HELP.spot, render: (e) => (e.spot ? formatDecimal(e.spot, 2) : '—'), mobileLabel: 'Asset' },
    { id: 'strike', header: 'Strike Price', help: FIELD_HELP.strike, render: (e) => <span className="font-semibold">{e.strike}</span>, mobileLabel: 'Strike' },
    {
      id: 'type',
      header: 'Type',
      align: 'center',
      help: 'Call (CE) or put (PE) leg of the move.',
      render: (e) => <span className={e.optionType === 'CE' ? 'text-bear' : 'text-bull'}>{e.optionType}</span>,
      mobileLabel: 'Type',
    },
    {
      id: 'moneyness',
      header: 'Moneyness',
      align: 'center',
      help: FIELD_HELP.moneyness,
      render: (e) => <MoneynessBadge value={moneyness(e.strike, e.spot, atm, e.optionType)} />,
      mobileLabel: 'Moneyness',
    },
    { id: 'close', header: 'Close Price', help: 'Latest traded price (LTP) of this option contract at the interval end.', render: (e) => (e.ltp ? formatDecimal(e.ltp, 2) : '—'), mobileLabel: 'Close' },
    { id: 'ltpChg', header: 'LTP Chg.', help: "Change in the option's premium over the interval.", render: (e) => <ValueDeltaCell value={e.ltpChange} />, mobileLabel: 'LTP Chg' },
    { id: 'oiChg', header: 'OI Chg.', help: FIELD_HELP.oiChange, render: (e) => <SignedCount value={e.oiChange} />, mobileLabel: 'OI Chg' },
    { id: 'interp', header: 'OI Interpretation', align: 'center', help: FIELD_HELP.oiInterpretation, render: (e) => <OiBadge4 value={e.interpretation} full />, mobileLabel: 'OI Int' },
  ];
}

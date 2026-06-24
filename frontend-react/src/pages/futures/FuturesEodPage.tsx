import { useEffect, useMemo, useState } from 'react';
import { useFuturesEod } from '../../api/oiAnalytics.ts';
import { useUnderlyings } from '../../api/instruments.ts';
import { foldFuturesEod, type FuturesEodRow } from '../../api/futuresEodFold.ts';
import { useSymbolContext } from '../../stores/symbolContext.store.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { OiBadge4 } from '../../components/atoms/OiBadge4.tsx';
import { SignedCount } from '../../components/atoms/SignedCount.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Futures EOD OI Analyzer (oipulse §futures/eod-oi-analyzer): day-by-day OHLC + OI + day-over-day
// deltas + interpretation for one futures contract. The BE /eod feed returns every captured contract
// for the underlying; the page picks one (a contract selector when more than one is present) and folds
// its daily series. NOTE: our capture is forward-only (history accrues from boot) — oipulse shows ~400
// days back to 2019; ours is shallow until history accrues (documented). Chart/cumulative/range toggles
// deferred.

const isoDaysAgo = (days: number): string => {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
};

const num = (n: number) => n.toLocaleString('en-IN');
const dec = (s: string | null, d = 2) => (s ? formatDecimal(s, d) : '—');

export function FuturesEodPage() {
  const name = useSymbolContext((s) => s.name);
  const setName = useSymbolContext((s) => s.setName);
  const underlyings = useUnderlyings();

  const to = useMemo(() => isoDaysAgo(0), []);
  const from = useMemo(() => isoDaysAgo(180), []);
  const q = useFuturesEod(name, from, to);

  // Group by contract; default-select the one with the most captured rows (the active front).
  const contracts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const r of q.data ?? []) counts.set(r.tradingsymbol, (counts.get(r.tradingsymbol) ?? 0) + 1);
    return [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([sym]) => sym);
  }, [q.data]);

  const [contract, setContract] = useState<string | null>(null);
  useEffect(() => {
    if (contracts.length && (!contract || !contracts.includes(contract))) setContract(contracts[0]);
  }, [contracts, contract]);

  const rows = useMemo(
    () => foldFuturesEod((q.data ?? []).filter((r) => r.tradingsymbol === contract)),
    [q.data, contract],
  );

  const columns: DataColumn<FuturesEodRow>[] = [
    { id: 'date', header: 'Date', align: 'left', sortValue: (r) => r.tradeDate, sortType: 'text', render: (r) => r.tradeDate, mobileLabel: 'Date' },
    { id: 'oi', header: 'Total OI', sortValue: (r) => r.totalOi, render: (r) => num(r.totalOi), mobileLabel: 'Total OI' },
    { id: 'open', header: 'Day Open', render: (r) => dec(r.dayOpen) },
    { id: 'high', header: 'Day High', render: (r) => dec(r.dayHigh) },
    { id: 'low', header: 'Day Low', render: (r) => dec(r.dayLow) },
    { id: 'vol', header: 'Volume', sortValue: (r) => r.volume, render: (r) => num(r.volume) },
    { id: 'ltp', header: 'LTP', render: (r) => dec(r.ltp), mobileLabel: 'LTP' },
    { id: 'range', header: 'Day Range', render: (r) => (r.dayRange ? `${formatDecimal(r.dayRange, 2)}${r.dayRangePct ? ` (${r.dayRangePct}%)` : ''}` : '—') },
    { id: 'ltpChg', header: 'LTP Change', render: (r) => <ValueDeltaCell value={r.ltpChange} />, mobileLabel: 'LTP Chg' },
    { id: 'oiChg', header: 'OI Change', sortValue: (r) => r.oiChange, render: (r) => <SignedCount value={r.oiChange} />, mobileLabel: 'OI Chg' },
    { id: 'ltpPct', header: '% Chng. in LTP', render: (r) => <ValueDeltaCell value={r.ltpChangePct} suffix="%" /> },
    { id: 'oiPct', header: '% Chng. in OI', render: (r) => <ValueDeltaCell value={r.oiChangePct} suffix="%" /> },
    { id: 'interp', header: 'OI Interpretation', align: 'center', render: (r) => <OiBadge4 value={r.interpretation} full />, mobileLabel: 'OI Int' },
  ];

  const nameOptions = underlyings.data && underlyings.data.length > 0 ? underlyings.data : ['NIFTY 50', 'NIFTY BANK'];

  return (
    <div>
      <PageHeader
        title="Futures EOD OI Analyzer"
        subtitle="Day-by-day OHLC, OI and day-over-day deltas for one futures contract"
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Select ariaLabel="Underlying" value={name} options={nameOptions} onChange={setName} />
        {contracts.length > 1 && (
          <Select ariaLabel="Contract" value={contract} options={contracts} onChange={setContract} />
        )}
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => rows.length === 0}
        empty={{ title: 'No EOD data captured for this contract yet.' }}
        errorTitle="Couldn't load futures EOD data"
        skeleton={<Skeleton variant="table-rows" rows={10} cols={13} />}
      >
        {() => (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(r) => r.tradeDate}
            pageSize={25}
            ariaLabel="Futures EOD OI analyzer"
            emptyMessage="No EOD data captured for this contract yet."
          />
        )}
      </QueryState>
    </div>
  );
}

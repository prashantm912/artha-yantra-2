import { useMemo, useState } from 'react';
import { useSectorStats } from '../../api/oiAnalytics.ts';
import type { SectorAgg, SectorStockChange } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Equity → Sector Stats (oipulse): a sector overview — per-sector roll-up cards (avg change +
// advancer/decliner split, computed from constituents since the sector-INDEX level isn't captured) +
// the per-stock factor table (Name, Chg %, Sector, Close, Y.Day Close). Latest bhavcopy session.

const num = (s: string | null): number => (s == null ? 0 : Number(s));
const f2 = (s: string | null) => (s == null ? '—' : formatDecimal(s, 2));

function SectorCard({ s }: { s: SectorAgg }) {
  const avg = num(s.avgChangePct);
  const tone = avg > 0 ? 'text-bull' : avg < 0 ? 'text-bear' : 'text-ay-muted';
  return (
    <div className="rounded-lg border border-ay-border bg-surface-1 p-3">
      <div className="truncate text-sm font-semibold text-ay-text" title={s.sector}>
        {s.sector}
      </div>
      <div className={`text-lg font-bold tabular-nums ${tone}`}>
        {s.avgChangePct == null ? '—' : `${avg >= 0 ? '+' : ''}${avg.toFixed(2)}%`}
      </div>
      <div className="text-xs text-ay-muted">
        <span className="text-bull">{s.positive}▲</span> · <span className="text-bear">{s.negative}▼</span> ·{' '}
        {s.total} total
      </div>
    </div>
  );
}

export function SectorStatsPage() {
  const q = useSectorStats();
  const data = q.data ?? null;
  const [search, setSearch] = useState('');

  const stocks = useMemo(() => {
    const all = data?.stocks ?? [];
    const needle = search.trim().toUpperCase();
    return needle
      ? all.filter(
          (r) => r.symbol.toUpperCase().includes(needle) || r.sector.toUpperCase().includes(needle),
        )
      : all;
  }, [data, search]);

  const columns: DataColumn<SectorStockChange>[] = useMemo(
    () => [
      { id: 'symbol', header: 'Name', align: 'left', render: (r) => r.symbol, sortValue: (r) => r.symbol, mobileLabel: 'Name' },
      {
        id: 'chg',
        header: 'Chg. %',
        render: (r) => <ValueDeltaCell value={r.changePct} suffix="%" />,
        sortValue: (r) => num(r.changePct),
        mobileLabel: 'Chg. %',
      },
      { id: 'sector', header: 'Sector', align: 'left', render: (r) => r.sector, sortValue: (r) => r.sector, mobileLabel: 'Sector' },
      { id: 'close', header: 'Close', render: (r) => f2(r.close), sortValue: (r) => num(r.close), mobileLabel: 'Close' },
      { id: 'prev', header: 'Y.Day Close', render: (r) => f2(r.prevClose), mobileLabel: 'Y.Day Close' },
    ],
    [],
  );

  return (
    <div>
      <h1 className="mb-2 text-base font-semibold text-ay-text">Sector Stats</h1>
      <p className="mb-3 text-xs text-ay-muted">
        Per-sector average change (from constituents) + the stock factor table
        {data?.asOf ? ` · as on ${data.asOf}` : ''}
      </p>

      {data && (
        <div className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {data.sectors.map((s) => (
            <SectorCard key={s.sector} s={s} />
          ))}
        </div>
      )}

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search name or sector…"
          aria-label="Search name or sector"
          className="h-9 w-56 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <GoButton onClick={() => void q.refetch()} loading={q.isFetching} />
      </div>

      <DataTable
        columns={columns}
        rows={stocks}
        rowKey={(r) => r.symbol}
        pageSize={50}
        ariaLabel="Stock factors by sector"
        emptyMessage="No sector data yet."
      />
    </div>
  );
}

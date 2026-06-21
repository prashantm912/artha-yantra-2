import { useMemo, useState } from 'react';
import { useFuturesSpurt } from '../../api/oiAnalytics.ts';
import type { FutSpurt } from '../../api/types.ts';
import type { OiInterpretation } from '../../core/oiInterpretation.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { SignedCount } from '../../components/atoms/SignedCount.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Futures OI Spurt (oipulse §futures/oi-spurt): the 2×2 OI scanner — every captured futures contract
// bucketed by its 4-state interpretation, each quadrant a sortable, paginated (8) table. Off the
// /futures/spurt feed (now carrying prevClose + LTP Chg%). NOTE: our capture universe is the index
// futures + 17 bank-sector stock futures (not all ~320 F&O stocks oipulse scans) — a structurally
// faithful, reduced-universe replica (documented; the all-F&O capture is a deferred expansion).

interface Quadrant {
  state: OiInterpretation;
  title: string;
  subtitle: string;
}

const QUADRANTS: Quadrant[] = [
  { state: 'LONG_BUILDUP', title: 'Long Build Up', subtitle: 'price ↑ · OI ↑' },
  { state: 'SHORT_BUILDUP', title: 'Short Build Up', subtitle: 'price ↓ · OI ↑' },
  { state: 'SHORT_COVERING', title: 'Short Covering', subtitle: 'price ↑ · OI ↓' },
  { state: 'LONG_UNWINDING', title: 'Long Unwinding', subtitle: 'price ↓ · OI ↓' },
];

const num = (n: number) => n.toLocaleString('en-IN');

const COLUMNS: DataColumn<FutSpurt>[] = [
  { id: 'name', header: 'Name', align: 'left', sortValue: (r) => r.tradingsymbol, sortType: 'text', render: (r) => r.tradingsymbol, mobileLabel: 'Name' },
  { id: 'ltp', header: 'LTP', sortValue: (r) => r.ltp, sortType: 'decimal', render: (r) => (r.ltp ? formatDecimal(r.ltp, 2) : '—'), mobileLabel: 'LTP' },
  { id: 'prev', header: 'Prev. Close', sortValue: (r) => r.prevClose, sortType: 'decimal', render: (r) => (r.prevClose ? formatDecimal(r.prevClose, 2) : '—'), cellClassName: () => 'text-ay-muted' },
  { id: 'ltpPct', header: 'LTP Chg %', sortValue: (r) => r.pricePct, sortType: 'decimal', render: (r) => <ValueDeltaCell value={r.pricePct} suffix="%" />, mobileLabel: 'LTP %' },
  { id: 'oiPct', header: 'OI Chg %', sortValue: (r) => r.spurtPct, sortType: 'decimal', render: (r) => <ValueDeltaCell value={r.spurtPct} suffix="%" />, mobileLabel: 'OI %' },
  { id: 'newOi', header: 'New OI', sortValue: (r) => r.oi, render: (r) => num(r.oi) },
  { id: 'oldOi', header: 'Old OI', sortValue: (r) => r.oi - r.oiChange, render: (r) => <span className="text-ay-muted">{num(r.oi - r.oiChange)}</span> },
  { id: 'oiChg', header: 'OI Chg.', sortValue: (r) => r.oiChange, render: (r) => <SignedCount value={r.oiChange} />, mobileLabel: 'OI Chg' },
];

export function FuturesOiSpurtPage() {
  const q = useFuturesSpurt();
  const [search, setSearch] = useState('');

  const items = useMemo(() => q.data?.items ?? [], [q.data]);
  const byQuadrant = useMemo(() => {
    const needle = search.trim().toUpperCase();
    const filtered = needle ? items.filter((r) => r.tradingsymbol.toUpperCase().includes(needle)) : items;
    const groups: Record<OiInterpretation, FutSpurt[]> = {
      LONG_BUILDUP: [],
      SHORT_BUILDUP: [],
      SHORT_COVERING: [],
      LONG_UNWINDING: [],
    };
    for (const r of filtered) groups[r.interpretation].push(r);
    return groups;
  }, [items, search]);

  return (
    <div>
      <h1 className="ay-sr-only">Futures OI Spurt</h1>

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

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        {QUADRANTS.map((qd) => (
          <div key={qd.state} className="rounded border border-ay-border bg-surface-1 p-2">
            <div className="mb-1 flex items-baseline justify-between">
              <div>
                <span className="text-sm font-semibold text-ay-text">{qd.title}</span>
                <span className="ml-2 text-xs text-ay-muted">{qd.subtitle}</span>
              </div>
              <span className="text-xs text-ay-muted tabular-nums">{byQuadrant[qd.state].length}</span>
            </div>
            <DataTable
              columns={COLUMNS}
              rows={byQuadrant[qd.state]}
              rowKey={(r) => r.tradingsymbol}
              pageSize={8}
              initialSort={{ id: 'oiChg', dir: 'desc' }}
              ariaLabel={qd.title}
              emptyMessage="No contracts."
            />
          </div>
        ))}
      </div>
    </div>
  );
}

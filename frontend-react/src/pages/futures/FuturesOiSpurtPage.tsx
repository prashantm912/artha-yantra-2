import { useMemo, useState } from 'react';
import { useFuturesSpurt } from '../../api/oiAnalytics.ts';
import type { FutSpurt } from '../../api/types.ts';
import type { OiInterpretation } from '../../core/oiInterpretation.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { SignedCount } from '../../components/atoms/SignedCount.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { formatDecimal } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';

// Futures OI Spurt (oipulse §futures/oi-spurt): the 2×2 OI scanner — every captured futures contract
// bucketed by its 4-state interpretation, each quadrant a sortable, paginated (8) table. Off the
// /futures/spurt feed (now carrying prevClose + LTP Chg%). NOTE: our capture universe is the index
// futures + 17 bank-sector stock futures (not all ~320 F&O stocks oipulse scans) — a structurally
// faithful, reduced-universe replica (documented; the all-F&O capture is a deferred expansion).
// Revamp rollout: visible display-face H1 (was ay-sr-only — text preserved) + QueryState over the
// quadrant grid (error/empty/pending split). Search filters client-side, so emptiness stays per-table.

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
  { id: 'name', header: 'Name', align: 'left', sortValue: (r) => r.tradingsymbol, sortType: 'text', render: (r) => r.tradingsymbol, mobileLabel: 'Name', help: 'The futures contract trading symbol.' },
  { id: 'ltp', header: 'LTP', sortValue: (r) => r.ltp, sortType: 'decimal', render: (r) => (r.ltp ? formatDecimal(r.ltp, 2) : '—'), mobileLabel: 'LTP', help: FIELD_HELP.ltp },
  { id: 'prev', header: 'Prev. Close', sortValue: (r) => r.prevClose, sortType: 'decimal', render: (r) => (r.prevClose ? formatDecimal(r.prevClose, 2) : '—'), cellClassName: () => 'text-ay-muted', help: FIELD_HELP.prevClose },
  { id: 'ltpPct', header: 'LTP Chg %', sortValue: (r) => r.pricePct, sortType: 'decimal', render: (r) => <ValueDeltaCell value={r.pricePct} suffix="%" />, mobileLabel: 'LTP %', help: FIELD_HELP.changePct },
  { id: 'oiPct', header: 'OI Chg %', sortValue: (r) => r.spurtPct, sortType: 'decimal', render: (r) => <ValueDeltaCell value={r.spurtPct} suffix="%" />, mobileLabel: 'OI %', help: FIELD_HELP.oiChangePct },
  { id: 'newOi', header: 'New OI', sortValue: (r) => r.oi, render: (r) => num(r.oi), help: 'Open Interest at the latest reading — the current outstanding contracts.' },
  { id: 'oldOi', header: 'Old OI', sortValue: (r) => r.oi - r.oiChange, render: (r) => <span className="text-ay-muted">{num(r.oi - r.oiChange)}</span>, help: 'Open Interest at the prior reading — the baseline the change is measured against.' },
  { id: 'oiChg', header: 'OI Chg.', sortValue: (r) => r.oiChange, render: (r) => <SignedCount value={r.oiChange} />, mobileLabel: 'OI Chg', help: FIELD_HELP.oiChange },
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
    <LoadBeat>
      <PageHeader
        title="Futures OI Spurt"
        subtitle="Every captured futures contract bucketed by its 4-state OI interpretation"
        help="Sorts every captured futures contract into four OI-action quadrants (long buildup, short buildup, short covering, long unwinding) so you can spot where fresh positions are being added or unwound."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry={false} showInterval />
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search symbol"
          aria-label="Search symbol"
          title="Filter the quadrants to contracts whose symbol contains this text"
          className="h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => items.length === 0}
        empty={{ title: 'No futures contracts captured yet.' }}
        errorTitle="Couldn't load futures OI spurt"
        skeleton={
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            {QUADRANTS.map((qd) => (
              <Skeleton key={qd.state} variant="table-rows" rows={8} cols={8} />
            ))}
          </div>
        }
      >
        {() => (
          <BeatBlock className="grid grid-cols-1 gap-3 md:grid-cols-2">
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
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

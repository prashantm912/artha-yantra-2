import { useOpenHighStrategy } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { cn } from '../../lib/cn.ts';
import { formatDecimal } from '../../lib/decimal.ts';
import type { OpenHighStrategyLeg, OpenHighStrategyStrike } from '../../api/types.ts';

// Open & High Strategy — oipulse §strategies/open-high-strategy (Siva #2). Mirrored CE | Strike | PE
// scan: for each ATM-window strike the latest captured session's Open=High (Call) / Open=Low (Put)
// premium-reversion mark, a "Hit" badge when it triggered, the historical TRIGGER PROBABILITY (hit-
// rate over the prior captured sessions), and the % fall of close below its high (the LTP-distance
// reversion gauge). One /open-high-strategy read folds the EOD day-rollup of captured snapshots (zero
// new capture). Capture is forward-only, so the probability window is shallow until sessions accrue.

const dec = (s: string | null) => (s ? formatDecimal(s, 2) : '—');

/** Pattern badge: "O=H" (Call) / "O=L" (Put) when the leg's latest session formed it, else "—". The
 * text is the cue; the ring tone is supportive only (a11y — readable without colour). */
function PatternBadge({ leg }: { leg: OpenHighStrategyLeg | null }) {
  if (!leg) return <span className="text-ay-muted">—</span>;
  const isCall = leg.optionType === 'CE';
  const fired = isCall ? leg.ohMark : leg.olMark;
  if (!fired) return <span className="text-ay-muted">—</span>;
  const label = isCall ? 'O=H' : 'O=L';
  return (
    <span
      className={cn(
        'inline-block whitespace-nowrap rounded px-1.5 py-0.5 text-xs font-semibold ring-1',
        'text-bull ring-bull/40',
      )}
      aria-label={isCall ? 'Open equals High' : 'Open equals Low'}
    >
      {leg.triggered ? `${label} Hit` : label}
    </span>
  );
}

/** Probability % cell — the historical trigger odds; muted dash when no prior session accrued. */
function ProbabilityCell({ leg }: { leg: OpenHighStrategyLeg | null }) {
  if (!leg || leg.probability == null) return <span className="text-ay-muted">—</span>;
  return (
    <span className="tabular-nums" title={`${leg.hits}/${leg.sessions - 1} prior sessions`}>
      {formatDecimal(leg.probability, 2)}%
    </span>
  );
}

/** Symmetric CE | Strike | PE columns: a Call (CE) half, the centre Strike, then a mirrored Put (PE) half. */
const columns: DataColumn<OpenHighStrategyStrike>[] = [
  { id: 'ceProb', header: 'Call Prob', render: (r) => <ProbabilityCell leg={r.ce} />, mobileLabel: 'Call Prob' },
  { id: 'cePattern', header: 'Call O=H', align: 'center', render: (r) => <PatternBadge leg={r.ce} />, mobileLabel: 'Call O=H' },
  { id: 'ceFall', header: 'Call Fall%', render: (r) => <ValueDeltaCell value={r.ce?.fallPctFromHigh ?? null} suffix="%" />, mobileLabel: 'Call Fall%' },
  { id: 'ceClose', header: 'Call LTP', render: (r) => dec(r.ce?.latestClose ?? null), mobileLabel: 'Call LTP' },
  {
    id: 'strike',
    header: 'Strike',
    align: 'center',
    sortValue: (r) => r.strike,
    sortType: 'decimal',
    render: (r) => <span className="font-semibold text-ay-text">{dec(r.strike)}</span>,
    mobileLabel: 'Strike',
  },
  { id: 'peClose', header: 'Put LTP', render: (r) => dec(r.pe?.latestClose ?? null), mobileLabel: 'Put LTP' },
  { id: 'peFall', header: 'Put Fall%', render: (r) => <ValueDeltaCell value={r.pe?.fallPctFromHigh ?? null} suffix="%" />, mobileLabel: 'Put Fall%' },
  { id: 'pePattern', header: 'Put O=L', align: 'center', render: (r) => <PatternBadge leg={r.pe} />, mobileLabel: 'Put O=L' },
  { id: 'peProb', header: 'Put Prob', render: (r) => <ProbabilityCell leg={r.pe} />, mobileLabel: 'Put Prob' },
];

export function OpenHighStrategyPage() {
  const q = useOpenHighStrategy();
  const rows = q.data ?? [];

  return (
    <div>
      <h1 className="ay-sr-only">Open and High Strategy</h1>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      {rows.length === 0 && !q.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No Open=High / Open=Low history — pick an index + expiry with captured chain snapshots. The
          probability window accrues from boot, so it is shallow until sessions build up.
        </p>
      )}

      {rows.length > 0 && (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(r) => r.strike}
          pageSize={21}
          ariaLabel="Open and High Strategy scan"
          emptyMessage="No Open=High / Open=Low strikes in the ATM window yet."
          initialSort={{ id: 'strike', dir: 'asc' }}
        />
      )}
    </div>
  );
}

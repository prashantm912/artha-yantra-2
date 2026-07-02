import { Target } from 'lucide-react';
import { useOpenHighStrategy } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { cn } from '../../lib/cn.ts';
import { formatDecimal } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';
import type { OpenHighStrategyLeg, OpenHighStrategyStrike } from '../../api/types.ts';

// Open & High Strategy — oipulse §strategies/open-high-strategy (Siva #2). Mirrored CE | Strike | PE
// scan: for each ATM-window strike, the PRIOR session's Open=High (Call) / Open=Low (Put) premium-
// reversion pattern, the live day's New D.High/D.Low + LTP, a "Hit ✓ HH:mm" badge when the live day
// broke the graded day's extreme (audit §10.2-8), the historical TRIGGER PROBABILITY (hit-rate over
// prior captured sessions), and the live % fall below the day high (the reversion gauge). One
// /open-high-strategy read (EOD rollup + the viewed day's buckets). Capture is forward-only, so the
// probability window is shallow until sessions accrue.

const dec = (s: string | null) => (s ? formatDecimal(s, 2) : '—');

/** Pattern badge: "O=H" (Call) / "O=L" (Put) when the graded session formed it; a live break adds
 * "Hit ✓ HH:mm" (amber, oipulse-style). Text is the cue; ring tone supportive only (a11y). */
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
        leg.triggered ? 'text-warn ring-warn/40' : 'text-bull ring-bull/40',
      )}
      aria-label={isCall ? 'Open equals High' : 'Open equals Low'}
    >
      {leg.triggered
        ? `${label} Hit ✓${leg.triggeredTime ? ` ${leg.triggeredTime}` : ''}`
        : label}
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
/** The viewed day's running High / Low for a leg ("126.00 / 95.00"). */
function NewHiLo({ leg }: { leg: OpenHighStrategyLeg | null }) {
  if (!leg || (leg.newDayHigh == null && leg.newDayLow == null)) {
    return <span className="text-ay-muted">—</span>;
  }
  return (
    <span className="whitespace-nowrap tabular-nums">
      {dec(leg.newDayHigh)} / {dec(leg.newDayLow)}
    </span>
  );
}

const columns: DataColumn<OpenHighStrategyStrike>[] = [
  { id: 'ceProb', header: 'Call Prob', render: (r) => <ProbabilityCell leg={r.ce} />, mobileLabel: 'Call Prob', help: 'Historical odds the Call formed the Open=High pattern, over prior captured sessions.' },
  { id: 'cePattern', header: 'Call O=H', align: 'center', render: (r) => <PatternBadge leg={r.ce} />, mobileLabel: 'Call O=H', help: "Shows O=H when the Call opened at the GRADED session's high; the amber Hit ✓ + time marks the live day breaking that high." },
  { id: 'ceNewHl', header: 'Call New D.H/L', render: (r) => <NewHiLo leg={r.ce} />, mobileLabel: 'Call D.H/L', help: "The Call premium's running high / low on the viewed day." },
  { id: 'ceFall', header: 'Call Fall%', render: (r) => <ValueDeltaCell value={r.ce?.fallPctFromHigh ?? null} suffix="%" />, mobileLabel: 'Call Fall%', help: 'How far the Call LTP sits below its day high, in percent — the reversion gauge.' },
  { id: 'ceClose', header: 'Call LTP', render: (r) => dec(r.ce?.liveLtp ?? r.ce?.latestClose ?? null), mobileLabel: 'Call LTP', help: FIELD_HELP.ltp },
  {
    id: 'strike',
    header: 'Strike',
    align: 'center',
    sortValue: (r) => r.strike,
    sortType: 'decimal',
    render: (r) => <span className="font-semibold text-ay-text">{dec(r.strike)}</span>,
    mobileLabel: 'Strike',
    help: FIELD_HELP.strike,
  },
  { id: 'peClose', header: 'Put LTP', render: (r) => dec(r.pe?.liveLtp ?? r.pe?.latestClose ?? null), mobileLabel: 'Put LTP', help: FIELD_HELP.ltp },
  { id: 'peFall', header: 'Put Fall%', render: (r) => <ValueDeltaCell value={r.pe?.fallPctFromHigh ?? null} suffix="%" />, mobileLabel: 'Put Fall%', help: 'How far the Put LTP sits below its day high, in percent — the reversion gauge.' },
  { id: 'peNewHl', header: 'Put New D.H/L', render: (r) => <NewHiLo leg={r.pe} />, mobileLabel: 'Put D.H/L', help: "The Put premium's running high / low on the viewed day." },
  { id: 'pePattern', header: 'Put O=L', align: 'center', render: (r) => <PatternBadge leg={r.pe} />, mobileLabel: 'Put O=L', help: "Shows O=L when the Put opened at the GRADED session's low; the amber Hit ✓ + time marks the live day breaking that low." },
  { id: 'peProb', header: 'Put Prob', render: (r) => <ProbabilityCell leg={r.pe} />, mobileLabel: 'Put Prob', help: 'Historical odds the Put formed the Open=Low pattern, over prior captured sessions.' },
];

export function OpenHighStrategyPage() {
  const q = useOpenHighStrategy();
  const rows = q.data ?? [];

  return (
    <LoadBeat>
      <PageHeader
        title="Open and High Strategy"
        help="Scans each strike for the premium-reversion setup where a Call opens at its high (or a Put at its low), with the historical hit-rate and how far price has reverted since."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => rows.length === 0}
        empty={{
          icon: Target,
          title:
            'No Open=High / Open=Low history — pick an index + expiry with captured chain snapshots. The probability window accrues from boot, so it is shallow until sessions build up.',
        }}
        errorTitle="Couldn't load Open and High Strategy"
        skeleton={<Skeleton variant="table-rows" rows={12} cols={9} />}
      >
        {() => (
          <BeatBlock>
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(r) => r.strike}
              pageSize={21}
              ariaLabel="Open and High Strategy scan"
              emptyMessage="No Open=High / Open=Low strikes in the ATM window yet."
              initialSort={{ id: 'strike', dir: 'asc' }}
            />
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

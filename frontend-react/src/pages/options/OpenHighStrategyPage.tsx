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
  { id: 'ceProb', header: 'Call Prob', render: (r) => <ProbabilityCell leg={r.ce} />, mobileLabel: 'Call Prob', help: 'Historical odds the Call formed the Open=High pattern, over prior captured sessions.' },
  { id: 'cePattern', header: 'Call O=H', align: 'center', render: (r) => <PatternBadge leg={r.ce} />, mobileLabel: 'Call O=H', help: 'Shows O=H when the Call opened at its session high; "Hit" marks the reversion trade triggering.' },
  { id: 'ceFall', header: 'Call Fall%', render: (r) => <ValueDeltaCell value={r.ce?.fallPctFromHigh ?? null} suffix="%" />, mobileLabel: 'Call Fall%', help: 'How far the Call has fallen below its session high, in percent — the reversion gauge.' },
  { id: 'ceClose', header: 'Call LTP', render: (r) => dec(r.ce?.latestClose ?? null), mobileLabel: 'Call LTP', help: FIELD_HELP.ltp },
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
  { id: 'peClose', header: 'Put LTP', render: (r) => dec(r.pe?.latestClose ?? null), mobileLabel: 'Put LTP', help: FIELD_HELP.ltp },
  { id: 'peFall', header: 'Put Fall%', render: (r) => <ValueDeltaCell value={r.pe?.fallPctFromHigh ?? null} suffix="%" />, mobileLabel: 'Put Fall%', help: 'How far the Put has fallen below its session high, in percent — the reversion gauge.' },
  { id: 'pePattern', header: 'Put O=L', align: 'center', render: (r) => <PatternBadge leg={r.pe} />, mobileLabel: 'Put O=L', help: 'Shows O=L when the Put opened at its session low; "Hit" marks the reversion trade triggering.' },
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

import { QueryState } from './QueryState.tsx';
import { Skeleton } from './Skeletons.tsx';
import { BeatBlock } from './LoadBeat.tsx';
import { formatDecimal } from '../lib/decimal.ts';
import {
  useScreenerAttrition,
  useScreenerDiff,
  type DiffRow,
  type ScreenerFamily,
} from '../api/screenerHistory.ts';

// The screener time-machine panel (audit §6.7 Phase-3): the funnel stage-attrition (scanned →
// per-gate → buyable) and the day-over-day entered/left passer diff for a screen date. Shared by both
// screener pages — family-parameterized over the symmetric /diff + /attrition endpoints.

export function ScreenerHistoryPanel({
  family,
  asOf,
}: {
  family: ScreenerFamily;
  asOf: string | null;
}) {
  const attrition = useScreenerAttrition(family, asOf);
  const diff = useScreenerDiff(family, asOf);

  return (
    <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
      <BeatBlock className="rounded-lg border border-ay-border">
        <div className="border-b border-ay-border px-3 py-2">
          <h3 className="text-sm font-semibold text-ay-text">Funnel attrition</h3>
          <p className="mt-0.5 text-xs text-ay-muted">
            How the scanned universe narrows to the actionable buyable set.
          </p>
        </div>
        <QueryState
          query={attrition}
          errorTitle="Couldn't load attrition"
          skeleton={<Skeleton variant="table-rows" rows={6} cols={2} />}
        >
          {(a) => {
            const scanned = Math.max(a.scanned, 1);
            const step = (label: string, count: number, tone = 'accent') => (
              <div className="flex items-center gap-2 py-1" key={label}>
                <span className="w-28 shrink-0 text-xs text-ay-muted">{label}</span>
                <span className="h-3 flex-1 overflow-hidden rounded bg-surface-2">
                  <span
                    className={tone === 'bull' ? 'block h-full bg-bull' : 'block h-full bg-accent'}
                    style={{ width: `${Math.min(100, (count / scanned) * 100)}%` }}
                  />
                </span>
                <span className="w-12 shrink-0 text-right text-xs tabular-nums text-ay-text">{count}</span>
              </div>
            );
            return (
              <div className="px-3 py-2">
                {step('Scanned', a.scanned)}
                {a.gates.map((g) => step(`Pass gate ${g.gate}`, g.passed))}
                {step('Pass all gates', a.passesAll)}
                {step('Buyable', a.buyable, 'bull')}
                {step('On deck', a.onDeck)}
                {step('Watch', a.watch)}
              </div>
            );
          }}
        </QueryState>
      </BeatBlock>

      <BeatBlock className="rounded-lg border border-ay-border">
        <div className="border-b border-ay-border px-3 py-2">
          <h3 className="text-sm font-semibold text-ay-text">Day-over-day change</h3>
          <p className="mt-0.5 text-xs text-ay-muted">
            Passers that entered or left vs the prior screen date.
          </p>
        </div>
        <QueryState
          query={diff}
          errorTitle="Couldn't load the diff"
          skeleton={<Skeleton variant="table-rows" rows={6} cols={2} />}
        >
          {(d) => (
            <div className="px-3 py-2">
              <p className="mb-2 text-xs text-ay-muted">
                {d.screenDate ?? '—'} vs {d.priorDate ?? 'no prior screen'}
              </p>
              <div className="grid grid-cols-2 gap-3">
                <DiffColumn title={`Entered (${d.enteredCount})`} tone="bull" rows={d.entered} />
                <DiffColumn title={`Left (${d.leftCount})`} tone="bear" rows={d.left} />
              </div>
            </div>
          )}
        </QueryState>
      </BeatBlock>
    </div>
  );
}

function DiffColumn({
  title,
  tone,
  rows,
}: {
  title: string;
  tone: 'bull' | 'bear';
  rows: DiffRow[];
}) {
  const chip =
    tone === 'bull' ? 'bg-bull/15 text-bull' : 'bg-bear/15 text-bear';
  return (
    <div>
      <h4 className={tone === 'bull' ? 'mb-1 text-xs font-semibold text-bull' : 'mb-1 text-xs font-semibold text-bear'}>
        {title}
      </h4>
      <ul className="flex max-h-64 flex-wrap gap-1 overflow-auto">
        {rows.map((r) => (
          <li
            key={r.symbol}
            className={`rounded px-1.5 py-0.5 text-xs tabular-nums ${chip}`}
            title={r.rsRank ? `RS ${formatDecimal(r.rsRank, 0)}` : undefined}
          >
            {r.symbol}
          </li>
        ))}
        {rows.length === 0 && <li className="text-xs text-ay-muted">None.</li>}
      </ul>
    </div>
  );
}

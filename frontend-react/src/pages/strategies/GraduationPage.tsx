import { cn } from '../../lib/cn.ts';
import { formatDecimal, isNegative, multiplyByInt } from '../../lib/decimal.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import {
  useGraduationBoard,
  useGraduationPromotions,
  type Criterion,
  type StrategyGraduation,
} from '../../api/graduation.ts';

// /strategies/graduation (F7) — the per-strategy "is this ready to graduate?" board. For every
// published+enabled strategy it shows its CLOSED paper-trade metrics (trades / net / win% / cost-
// adjusted PF / expectancy / max DD) scored against the configurable graduation thresholds, and the
// derived stage (PAPER while any criterion fails, TAKE_ELIGIBLE once all pass). MEASUREMENT ONLY —
// nothing here promotes or arms a strategy; graduating a strategy to live stays an owner action.

/** A green (pass) / red (fail) dot for one criterion, its detail in the title tooltip. */
function CriterionDot({ c }: { c: Criterion }) {
  return (
    <span
      title={`${c.name}: ${c.actual} (needs ${c.required}) — ${c.pass ? 'pass' : 'fail'}`}
      className={cn(
        'inline-block size-2.5 rounded-full',
        c.pass ? 'bg-bull' : 'bg-bear',
      )}
      aria-label={`${c.name} ${c.pass ? 'pass' : 'fail'}`}
    />
  );
}

/** The stage pill: TAKE_ELIGIBLE reads bullish, PAPER neutral. */
function StageBadge({ stage }: { stage: StrategyGraduation['stage'] }) {
  const eligible = stage === 'TAKE_ELIGIBLE';
  return (
    <span
      className={cn(
        'rounded px-1.5 py-0.5 text-xs font-semibold ring-1',
        eligible ? 'text-bull ring-bull/40' : 'text-ay-muted ring-ay-border',
      )}
    >
      {eligible ? 'Take-eligible' : 'Paper'}
    </span>
  );
}

/** Formats an optional decimal-string metric to a fixed dp, em-dash for null. */
function fmt(value: string | null, dp: number): string {
  return value == null ? '—' : formatDecimal(value, dp);
}

/** A signed money cell: negative reads bearish, non-negative bullish. */
function Money({ value }: { value: string }) {
  const neg = isNegative(value);
  return (
    <span className={neg ? 'text-bear' : 'text-bull'}>
      {neg ? '' : '+'}₹{formatDecimal(value, 2)}
    </span>
  );
}

export function GraduationPage() {
  const board = useGraduationBoard();
  const promotions = useGraduationPromotions();
  const rows = board.data?.strategies ?? [];
  const th = board.data?.thresholds;
  const promos = promotions.data ?? [];
  // Board rows carry the display name/slug; the promotions row is id-keyed — join for a friendly label.
  const labelById = new Map(rows.map((s) => [s.strategyId, s]));

  return (
    <LoadBeat>
      <PageHeader
        title="Graduation"
        subtitle="Per-strategy paper-trade readiness vs the graduation thresholds — measurement only"
        help="For every published, enabled strategy this scores its CLOSED paper positions (attributed via signal → version → strategy; realized P&L is already net of costs) into trades, net, win-rate, cost-adjusted profit-factor, expectancy and max-drawdown, compared against the configurable graduation thresholds. A strategy reads Take-eligible once every criterion passes, else Paper. This board never promotes or arms anything — graduating a strategy to live is an owner action."
      />

      {th && (
        <p className="mb-3 text-xs text-ay-muted">
          Thresholds: ≥ {th.minTrades} trades · PF ≥ {formatDecimal(th.minProfitFactor, 2)} ·
          expectancy &gt; {formatDecimal(th.minExpectancy, 2)} · max DD ≤{' '}
          {formatDecimal(th.maxDrawdownPct, 2)}%
        </p>
      )}

      {promos.length > 0 && (
        <section className="mb-4">
          <h2 className="mb-2 flex flex-wrap items-center gap-2 text-sm font-semibold text-ay-text">
            <span className="rounded px-1.5 py-0.5 text-xs font-semibold text-bull ring-1 ring-bull/40">
              Graduated
            </span>
            <span className="text-ay-muted">strategies the F7 evaluator has marked graduated</span>
          </h2>
          <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
            <table className="w-full border-collapse text-sm">
              <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">Strategy</th>
                  <th className="px-2 py-2 font-medium">Graduated at</th>
                  <th className="px-2 py-2 text-right font-medium">Trades</th>
                  <th className="px-2 py-2 text-right font-medium">Expectancy</th>
                  <th className="px-2 py-2 text-right font-medium">Sharpe</th>
                  <th className="px-2 py-2 text-right font-medium">Max DD</th>
                </tr>
              </thead>
              <tbody>
                {promos.map((p) => {
                  const s = labelById.get(p.strategyId);
                  return (
                    <tr key={`${p.strategyId}:${p.graduatedAt}`} className="border-t border-ay-border">
                      <td className="px-2 py-2">
                        <div className="font-medium text-ay-text">{s?.name ?? p.strategyId}</div>
                        {s?.slug && <div className="text-xs text-ay-muted">{s.slug}</div>}
                      </td>
                      <td className="px-2 py-2 tabular-nums">
                        {p.graduatedAt ? p.graduatedAt.slice(0, 10) : '—'}
                      </td>
                      <td className="px-2 py-2 text-right tabular-nums">{p.trades}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{fmt(p.expectancy, 2)}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{fmt(p.sharpe, 2)}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{fmt(p.maxDrawdownPct, 2)}%</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </BeatBlock>
        </section>
      )}

      <QueryState
        query={board}
        isEmpty={() => rows.length === 0}
        empty={{ title: 'No published, enabled strategies yet — publish one to start measuring readiness.' }}
        errorTitle="Couldn't load the graduation board"
        skeleton={<Skeleton variant="table-rows" rows={6} cols={8} />}
      >
        {() => (
          <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
            <table className="w-full border-collapse text-sm">
              <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">Strategy</th>
                  <th className="px-2 py-2 font-medium">Stage</th>
                  <th className="px-2 py-2 text-right font-medium">Trades</th>
                  <th className="px-2 py-2 text-right font-medium">Net</th>
                  <th className="px-2 py-2 text-right font-medium">Win%</th>
                  <th className="px-2 py-2 text-right font-medium">PF</th>
                  <th className="px-2 py-2 text-right font-medium">Expectancy</th>
                  <th className="px-2 py-2 text-right font-medium">Max DD</th>
                  <th className="px-2 py-2 font-medium">Criteria</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((s) => (
                  <tr key={s.strategyId} className="border-t border-ay-border">
                    <td className="px-2 py-2">
                      <div className="font-medium text-ay-text">{s.name}</div>
                      <div className="text-xs text-ay-muted">{s.slug}</div>
                    </td>
                    <td className="px-2 py-2">
                      <StageBadge stage={s.stage} />
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums">{s.trades}</td>
                    <td className="px-2 py-2 text-right tabular-nums">
                      <Money value={s.netRealized} />
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums">
                      {/* Audit M21: winRate is a 0-1 fraction on the wire — render as a percent
                          under the "Win%" header (was "0.7500", now "75.0%"). */}
                      {s.winRate == null ? '—' : `${formatDecimal(multiplyByInt(s.winRate, 100), 1)}%`}
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums">{fmt(s.profitFactor, 2)}</td>
                    <td className="px-2 py-2 text-right tabular-nums">
                      <span className={isNegative(s.expectancy) ? 'text-bear' : 'text-ay-text'}>
                        {fmt(s.expectancy, 2)}
                      </span>
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums">{fmt(s.maxDrawdownPct, 2)}%</td>
                    <td className="px-2 py-2">
                      <span className="flex items-center gap-1.5">
                        {s.criteria.map((c) => (
                          <CriterionDot key={c.name} c={c} />
                        ))}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

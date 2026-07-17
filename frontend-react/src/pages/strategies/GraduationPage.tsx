import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { formatDecimal, isNegative, multiplyByInt } from '../../lib/decimal.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import {
  useGraduationBoard,
  useGraduationPromotions,
  type Criterion,
  type Promotion,
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
  const rows = useMemo(() => board.data?.strategies ?? [], [board.data]);
  const th = board.data?.thresholds;
  const promos = promotions.data ?? [];
  // Board rows carry the display name/slug; the promotions row is id-keyed — join for a friendly label.
  const labelById = useMemo(() => new Map(rows.map((s) => [s.strategyId, s])), [rows]);

  const promoColumns: DataColumn<Promotion>[] = useMemo(
    () => [
      {
        id: 'strategy',
        header: 'Strategy',
        align: 'left',
        mobileLabel: 'Strategy',
        render: (p) => {
          const s = labelById.get(p.strategyId);
          return (
            <>
              <div className="font-medium text-ay-text">{s?.name ?? p.strategyId}</div>
              {s?.slug && <div className="text-xs text-ay-muted">{s.slug}</div>}
            </>
          );
        },
      },
      {
        id: 'graduatedAt',
        header: 'Graduated at',
        align: 'left',
        mono: true,
        mobileLabel: 'Graduated at',
        render: (p) => (p.graduatedAt ? p.graduatedAt.slice(0, 10) : '—'),
      },
      { id: 'trades', header: 'Trades', mobileLabel: 'Trades', render: (p) => p.trades },
      {
        id: 'expectancy',
        header: 'Expectancy',
        mobileLabel: 'Expectancy',
        render: (p) => fmt(p.expectancy, 2),
      },
      { id: 'sharpe', header: 'Sharpe', mobileLabel: 'Sharpe', render: (p) => fmt(p.sharpe, 2) },
      {
        id: 'maxDd',
        header: 'Max DD',
        mobileLabel: 'Max DD',
        render: (p) => `${fmt(p.maxDrawdownPct, 2)}%`,
      },
    ],
    [labelById],
  );

  const boardColumns: DataColumn<StrategyGraduation>[] = useMemo(
    () => [
      {
        id: 'strategy',
        header: 'Strategy',
        align: 'left',
        mobileLabel: 'Strategy',
        render: (s) => (
          <>
            <div className="font-medium text-ay-text">{s.name}</div>
            <div className="text-xs text-ay-muted">{s.slug}</div>
            {/* INT-I3 dossier link (§8.6) — graduation evidence + crossing timeline + rejections. */}
            <Link
              to={`/insights/strategy-dossier/${s.strategyId}`}
              className="text-xs text-accent hover:underline"
            >
              Dossier →
            </Link>
          </>
        ),
      },
      {
        id: 'stage',
        header: 'Stage',
        align: 'left',
        mobileLabel: 'Stage',
        render: (s) => <StageBadge stage={s.stage} />,
      },
      { id: 'trades', header: 'Trades', mobileLabel: 'Trades', render: (s) => s.trades },
      {
        id: 'net',
        header: 'Net',
        mobileLabel: 'Net',
        render: (s) => <Money value={s.netRealized} />,
      },
      {
        id: 'winRate',
        header: 'Win%',
        mobileLabel: 'Win%',
        // Audit M21: winRate is a 0-1 fraction on the wire — render as a percent
        // under the "Win%" header (was "0.7500", now "75.0%").
        render: (s) =>
          s.winRate == null ? '—' : `${formatDecimal(multiplyByInt(s.winRate, 100), 1)}%`,
      },
      { id: 'pf', header: 'PF', mobileLabel: 'PF', render: (s) => fmt(s.profitFactor, 2) },
      {
        id: 'expectancy',
        header: 'Expectancy',
        mobileLabel: 'Expectancy',
        render: (s) => (
          <span className={isNegative(s.expectancy) ? 'text-bear' : 'text-ay-text'}>
            {fmt(s.expectancy, 2)}
          </span>
        ),
      },
      {
        id: 'maxDd',
        header: 'Max DD',
        mobileLabel: 'Max DD',
        render: (s) => `${fmt(s.maxDrawdownPct, 2)}%`,
      },
      {
        id: 'criteria',
        header: 'Criteria',
        align: 'left',
        mobileLabel: 'Criteria',
        render: (s) => (
          <span className="flex items-center gap-1.5">
            {s.criteria.map((c) => (
              <CriterionDot key={c.name} c={c} />
            ))}
          </span>
        ),
      },
    ],
    [],
  );

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
          <BeatBlock>
            <DataTable
              columns={promoColumns}
              rows={promos}
              rowKey={(p) => `${p.strategyId}:${p.graduatedAt}`}
              ariaLabel="Graduated strategies"
            />
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
          <BeatBlock>
            <DataTable
              columns={boardColumns}
              rows={rows}
              rowKey={(s) => s.strategyId}
              ariaLabel="Graduation board"
            />
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

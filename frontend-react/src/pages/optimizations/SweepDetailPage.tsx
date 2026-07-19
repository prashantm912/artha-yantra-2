import { useCallback, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import type { EChartsOption } from 'echarts';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { FoldDrilldownModal } from './FoldDrilldownModal.tsx';
import {
  paramStr,
  useSweepBest,
  useSweepTrials,
  usePromoteTrial,
  type BestRow,
  type GuardMetrics,
  type SortMode,
  type TrialState,
} from '../../api/optimizations.ts';

// /optimizations/:sweepId (master plan §20 parity, Phase 39): the sweep explorer + guard-aware
// leaderboard. Plateau sort is the default (raw one click away — guard 4); pruned/failed trials are
// FLAGGED, never hidden (guard 3). The leaderboard surfaces the four persisted §D.4 fold guards
// (regimes covered, min-OOS Sharpe, folds excluded, dataHash flagged on mismatch); a full-window
// trial shows a "no fold guards" badge. A live sweep stays fresh via refetchInterval. Promote →
// new draft. A row's "Folds" action opens the per-trial fold drill-down (OOS by regime, Phase 39).

const num = (v: number | null | undefined) => (v == null ? '—' : formatDecimal(String(v), 3));

// The most common dataHash among the leaderboard rows that carry one (null when none do). A row whose
// guard hash differs from this gets the "dataHash≠" flag.
function modalDataHash(rows: BestRow[]): string | null {
  const counts = new Map<string, number>();
  for (const row of rows) {
    const hash = row.guardMetrics?.dataHash;
    if (hash) counts.set(hash, (counts.get(hash) ?? 0) + 1);
  }
  let best: string | null = null;
  let bestCount = 0;
  for (const [hash, count] of counts) {
    if (count > bestCount) {
      best = hash;
      bestCount = count;
    }
  }
  return best;
}

// The compact guard cell: regimes-covered chips + min-OOS Sharpe + folds-excluded + a dataHash flag
// shown ONLY when this row diverges from the sweep's modal hash (like BacktestComparePage). A
// full-window/legacy trial (no guardMetrics) renders the "no fold guards" badge instead.
function GuardCell({ guard, hashMismatch }: { guard?: GuardMetrics | null; hashMismatch: boolean }) {
  if (!guard) {
    return <span className="text-xs text-ay-muted ring-1 ring-ay-border rounded px-1.5 py-0.5">no fold guards</span>;
  }
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {guard.regimesCovered.length > 0 ? (
        guard.regimesCovered.map((r) => (
          <span key={r} className="rounded bg-surface-2 px-1.5 py-0.5 text-[10px] font-semibold text-ay-text">
            {r}
          </span>
        ))
      ) : (
        <span className="text-[10px] text-ay-muted">no regimes traded</span>
      )}
      <span className="tabular-nums text-xs text-ay-muted" title="min per-regime OOS Sharpe">
        OOS≥ {num(guard.regimeOosMin)}
      </span>
      {guard.foldsExcluded != null && guard.foldsExcluded > 0 && (
        <span
          className="rounded bg-warn/15 px-1.5 py-0.5 text-[10px] font-semibold text-warn ring-1 ring-warn/40"
          title="folds dropped under min_trades"
        >
          −{guard.foldsExcluded} folds
        </span>
      )}
      {hashMismatch && (
        <span
          className="rounded bg-warn/15 px-1.5 py-0.5 text-[10px] font-semibold text-warn ring-1 ring-warn/40"
          title="dataHash differs from the rest of the sweep — not like-for-like"
        >
          dataHash≠
        </span>
      )}
    </div>
  );
}

const STATE_TONE: Record<TrialState, string> = {
  COMPLETE: 'text-bull ring-bull/40',
  RUNNING: 'text-accent ring-accent/40',
  PRUNED: 'text-warn ring-warn/40',
  FAILED: 'text-bear ring-bear/40',
};

const STATE_COLOR: Record<TrialState, keyof ChartTheme> = {
  COMPLETE: 'bull',
  RUNNING: 'accent',
  PRUNED: 'warn',
  FAILED: 'bear',
};

export function SweepDetailPage() {
  const { sweepId = '' } = useParams();
  const navigate = useNavigate();
  const [sort, setSort] = useState<SortMode>('plateau');
  const [promoteRow, setPromoteRow] = useState<BestRow | null>(null);
  const [foldsTrial, setFoldsTrial] = useState<number | null>(null);

  const best = useSweepBest(sweepId, sort);
  const trials = useSweepTrials(sweepId);
  const promote = usePromoteTrial(sweepId);

  const metric = best.data?.metric ?? 'sharpe';
  const bestRows = useMemo(() => best.data?.items ?? [], [best.data]);
  const trialRows = useMemo(() => trials.data?.items ?? [], [trials.data]);

  // The sweep's modal dataHash, so a single divergent row flags "dataHash≠" (a sweep should run
  // every trial over the same data window — a mismatch means not like-for-like, like the compare view).
  const modalHash = useMemo(() => modalDataHash(bestRows), [bestRows]);

  // Guard-aware leaderboard columns for the shared DataTable (§3.2, GraduationPage pattern): same
  // values/order/formatting as the hand-rolled table, with zebra + sticky header + a mobile card list.
  // The Guards/Params/Actions cells stay JSX renders; only the string HEADER is DataTable-typed, so the
  // sr-only Actions header rides `headerClassName: 'ay-sr-only'` (the shipped JobsPage pattern).
  const leaderboardColumns = useMemo<DataColumn<BestRow>[]>(
    () => [
      { id: 'trial', header: 'Trial', align: 'left', mono: true, mobileLabel: 'Trial', render: (row) => `#${row.trialNumber}` },
      { id: 'objective', header: `Objective (${metric})`, align: 'right', mobileLabel: `Objective (${metric})`, render: (row) => num(row.objective) },
      { id: 'plateau', header: 'Plateau', align: 'right', mobileLabel: 'Plateau', render: (row) => num(row.plateauObjective) },
      {
        id: 'guards',
        header: 'Guards',
        align: 'left',
        mobileLabel: 'Guards',
        render: (row) => (
          <GuardCell
            guard={row.guardMetrics}
            hashMismatch={
              !!row.guardMetrics?.dataHash && !!modalHash && row.guardMetrics.dataHash !== modalHash
            }
          />
        ),
      },
      {
        id: 'params',
        header: 'Params',
        align: 'left',
        mobileLabel: 'Params',
        cellClassName: () => 'font-mono text-xs text-ay-muted',
        render: (row) => paramStr(row),
      },
      {
        id: 'actions',
        header: 'Actions',
        align: 'right',
        headerClassName: 'ay-sr-only',
        mobileLabel: 'Actions',
        render: (row) => (
          <>
            <button
              type="button"
              onClick={() => setFoldsTrial(row.trialNumber)}
              title="Break this trial's out-of-sample results down by walk-forward fold and regime"
              className="px-1.5 text-xs text-accent hover:underline"
              aria-label={`Fold drill-down for trial ${row.trialNumber}`}
            >
              Folds
            </button>
            {row.backtestRunId && (
              <button
                type="button"
                onClick={() => navigate(`/backtests/${row.backtestRunId}`)}
                title="Open the full backtest results for this trial"
                className="px-1.5 text-xs text-accent hover:underline"
              >
                Results
              </button>
            )}
            <button
              type="button"
              onClick={() => setPromoteRow(row)}
              title="Apply this trial's parameter values onto the source strategy as a new draft"
              className="px-1.5 text-xs text-accent hover:underline"
            >
              Promote
            </button>
          </>
        ),
      },
    ],
    [metric, modalHash, navigate],
  );

  const scatterOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const byState = (state: TrialState) =>
        trialRows
          .filter((tr) => tr.state === state && tr.objectiveValues?.[metric] != null)
          .map((tr) => [tr.trialNumber, Number(tr.objectiveValues![metric])]);
      const states: TrialState[] = ['COMPLETE', 'RUNNING', 'PRUNED', 'FAILED'];
      return {
        textStyle: { color: t.text },
        tooltip: { trigger: 'item' },
        legend: { data: states, textStyle: { color: t.muted } },
        grid: { left: 56, right: 16, top: 30, bottom: 30 },
        xAxis: { type: 'value', name: 'trial #', axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.grid } } },
        yAxis: { type: 'value', name: metric, scale: true, axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.grid } } },
        series: states.map((state) => ({
          name: state,
          type: 'scatter',
          symbolSize: 8,
          itemStyle: { color: t[STATE_COLOR[state]] },
          data: byState(state),
        })),
      };
    },
    [trialRows, metric],
  );

  const doPromote = () => {
    if (!promoteRow) return;
    promote.mutate(promoteRow.trialNumber, { onSuccess: () => setPromoteRow(null) });
  };

  return (
    <LoadBeat>
      <PageHeader title="Sweep explorer" subtitle="Guard-aware leaderboard, trial scatter and promote-to-draft" help="Shows every parameter combination an optimization sweep tried, ranked on the leaderboard so you can spot the best trial and promote it to a new strategy draft." />
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <strong>Sweep {sweepId.slice(0, 8)}</strong>
        <span className="tabular-nums text-sm text-ay-muted">{trialRows.length} trials</span>
        <div className="flex-1" />
        <div role="group" aria-label="Sort" className="flex overflow-hidden rounded-md border border-ay-border text-sm">
          {(['plateau', 'raw'] as const).map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => setSort(s)}
              title={s === 'plateau' ? 'Rank by plateau-robust objective (params that hold up across nearby values)' : 'Rank by the raw single-best objective value'}
              className={cn('px-3 py-1.5 capitalize', sort === s ? 'bg-accent text-surface-0' : 'text-ay-text hover:bg-surface-2')}
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      <BeatBlock className="card shadow-e1">
        <EChart makeOption={scatterOption} height={280} ariaLabel="Trial objective scatter" />
      </BeatBlock>

      <h3 className="mb-2 mt-4 text-h3">Leaderboard ({sort} sort)</h3>
      <BeatBlock>
        <DataTable
          columns={leaderboardColumns}
          rows={bestRows}
          rowKey={(row) => String(row.trialNumber)}
          ariaLabel="Sweep leaderboard"
          emptyMessage="No completed trials yet."
        />
      </BeatBlock>

      <h3 className="mb-2 mt-4 text-h3">All trials (pruned/failed flagged, never hidden)</h3>
      <BeatBlock className="max-h-80 overflow-auto rounded-lg border border-ay-border">
        <table className="w-full border-collapse text-sm">
          <thead className="sticky top-0 bg-surface-1 text-left text-xs uppercase text-ay-muted">
            <tr>
              <th className="px-2 py-2 font-medium">Trial</th>
              <th className="px-2 py-2 font-medium">State</th>
              <th className="px-2 py-2 text-right font-medium">Objective</th>
              <th className="px-2 py-2 font-medium">Params</th>
            </tr>
          </thead>
          <tbody>
            {trialRows.map((tr) => (
              <tr key={tr.trialNumber} className="border-t border-ay-border">
                <td className="px-2 py-2 tabular-nums">#{tr.trialNumber}</td>
                <td className="px-2 py-2">
                  <span className={cn('rounded px-1.5 py-0.5 text-xs font-semibold ring-1', STATE_TONE[tr.state])}>
                    {tr.state}
                  </span>
                </td>
                <td className="px-2 py-2 text-right tabular-nums">
                  {tr.objectiveValues?.[metric] != null ? num(tr.objectiveValues[metric]) : '—'}
                </td>
                <td className="px-2 py-2 font-mono text-xs text-ay-muted">{paramStr(tr)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </BeatBlock>

      <FoldDrilldownModal sweepId={sweepId} trialNumber={foldsTrial} onClose={() => setFoldsTrial(null)} />

      {promoteRow && (
        <div className="fixed inset-0 z-30 grid place-items-center bg-black/40 p-4">
          <div className="w-full max-w-lg rounded-lg border border-ay-border bg-surface-1 p-4 shadow-xl">
            <h2 className="mb-2 text-base font-semibold">Promote trial → new draft</h2>
            <p className="mb-2 text-sm">
              Trial #{promoteRow.trialNumber} parameter values will be applied onto the source version
              as a new minor-bump draft:
            </p>
            <pre className="mb-3 overflow-auto rounded bg-surface-2 p-2 font-mono text-xs">{paramStr(promoteRow)}</pre>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setPromoteRow(null)}
                className="h-9 rounded-md border border-ay-border px-4 text-sm hover:border-accent"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={doPromote}
                disabled={promote.isPending}
                className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
              >
                Create draft
              </button>
            </div>
          </div>
        </div>
      )}
    </LoadBeat>
  );
}

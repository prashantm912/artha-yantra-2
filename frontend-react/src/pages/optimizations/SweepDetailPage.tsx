import { useCallback, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import type { EChartsOption } from 'echarts';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import {
  paramStr,
  useSweepBest,
  useSweepTrials,
  usePromoteTrial,
  type BestRow,
  type SortMode,
  type TrialState,
} from '../../api/optimizations.ts';

// /optimizations/:sweepId (master plan §20 parity, Phase 39): the sweep explorer + guard-aware
// leaderboard. Plateau sort is the default (raw one click away — guard 4); pruned/failed trials are
// FLAGGED, never hidden (guard 3). A live sweep stays fresh via refetchInterval. Promote → new draft.
// The per-trial fold drill-down dialog is deferred.

const num = (v: number | null | undefined) => (v == null ? '—' : formatDecimal(String(v), 3));

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

  const best = useSweepBest(sweepId, sort);
  const trials = useSweepTrials(sweepId);
  const promote = usePromoteTrial(sweepId);

  const metric = best.data?.metric ?? 'sharpe';
  const bestRows = useMemo(() => best.data?.items ?? [], [best.data]);
  const trialRows = useMemo(() => trials.data?.items ?? [], [trials.data]);

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
    <div>
      <h1 className="ay-sr-only">Sweep explorer</h1>
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
              className={cn('px-3 py-1.5 capitalize', sort === s ? 'bg-accent text-surface-0' : 'text-ay-text hover:bg-surface-2')}
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      <EChart makeOption={scatterOption} height={280} ariaLabel="Trial objective scatter" />

      <h3 className="mb-2 mt-4 text-base font-semibold">Leaderboard ({sort} sort)</h3>
      <div className="overflow-auto rounded-lg border border-ay-border">
        <table className="w-full border-collapse text-sm">
          <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
            <tr>
              <th className="px-2 py-2 font-medium">Trial</th>
              <th className="px-2 py-2 text-right font-medium">Objective ({metric})</th>
              <th className="px-2 py-2 text-right font-medium">Plateau</th>
              <th className="px-2 py-2 font-medium">Params</th>
              <th className="px-2 py-2"><span className="ay-sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {bestRows.map((row) => (
              <tr key={row.trialNumber} className="border-t border-ay-border">
                <td className="px-2 py-2 tabular-nums">#{row.trialNumber}</td>
                <td className="px-2 py-2 text-right tabular-nums">{num(row.objective)}</td>
                <td className="px-2 py-2 text-right tabular-nums">{num(row.plateauObjective)}</td>
                <td className="px-2 py-2 font-mono text-xs text-ay-muted">{paramStr(row)}</td>
                <td className="px-2 py-2 text-right">
                  {row.backtestRunId && (
                    <button
                      type="button"
                      onClick={() => navigate(`/backtests/${row.backtestRunId}`)}
                      className="px-1.5 text-xs text-accent hover:underline"
                    >
                      Results
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={() => setPromoteRow(row)}
                    className="px-1.5 text-xs text-accent hover:underline"
                  >
                    Promote
                  </button>
                </td>
              </tr>
            ))}
            {bestRows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-2 py-6 text-center text-ay-muted">No completed trials yet.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <h3 className="mb-2 mt-4 text-base font-semibold">All trials (pruned/failed flagged, never hidden)</h3>
      <div className="max-h-80 overflow-auto rounded-lg border border-ay-border">
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
      </div>

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
    </div>
  );
}

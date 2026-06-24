import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { EChartsOption } from 'echarts';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { useCompareResults, type BacktestResults } from '../../api/backtests.ts';

// /backtests/compare?ids=a,b,c (master plan §20 parity, E-11 screen 5): up to 6 runs — metric matrix
// (best per row highlighted, incl. benchmark-relative columns), a dataHash / universe-checksum
// mismatch banner when the runs are not like-for-like, and overlaid equity curves normalized to a 100
// start (so different capital bases compare). Trade-distribution histograms are deferred.

interface Row {
  key: string;
  label: string;
  higherBetter: boolean;
  suffix?: string;
  dp?: number;
}
const ROWS: Row[] = [
  { key: 'totalReturn', label: 'Total return', higherBetter: true, suffix: '%' },
  { key: 'cagr', label: 'CAGR', higherBetter: true, suffix: '%' },
  { key: 'sharpe', label: 'Sharpe', higherBetter: true },
  { key: 'sortino', label: 'Sortino', higherBetter: true },
  { key: 'maxDrawdown', label: 'Max drawdown', higherBetter: false, suffix: '%' },
  { key: 'winRate', label: 'Win rate', higherBetter: true, suffix: '%', dp: 1 },
  { key: 'profitFactor', label: 'Profit factor', higherBetter: true },
  { key: 'alpha', label: 'Alpha', higherBetter: true, dp: 4 },
  { key: 'beta', label: 'Beta', higherBetter: true, dp: 3 },
  { key: 'informationRatio', label: 'Information ratio', higherBetter: true, dp: 3 },
];

function metricValue(r: BacktestResults, key: string): number | null {
  const raw = r.metrics[key];
  return raw == null || Array.isArray(raw) ? null : Number(raw);
}

export function BacktestComparePage() {
  const [params] = useSearchParams();
  const ids = useMemo(
    () => (params.get('ids') ?? '').split(',').map((s) => s.trim()).filter(Boolean),
    [params],
  );
  const runs = useCompareResults(ids);

  const dataHashMismatch = new Set(runs.map((c) => c.data.dataHash)).size > 1;
  const universeMismatch = new Set(runs.map((c) => c.data.universeChecksum ?? '∅')).size > 1;

  const matrix = useMemo(
    () =>
      ROWS.map((row) => {
        const vals = runs.map((r) => metricValue(r.data, row.key));
        const present = vals.filter((v): v is number => v != null);
        const best = present.length ? (row.higherBetter ? Math.max(...present) : Math.min(...present)) : null;
        return {
          ...row,
          cells: runs.map((r, i) => {
            const v = vals[i];
            return {
              id: r.id,
              text: v == null ? '—' : `${formatDecimal(String(v), row.dp ?? 2)}${row.suffix ?? ''}`,
              best: v != null && best != null && v === best && present.length > 1,
            };
          }),
        };
      }),
    [runs],
  );

  const equityOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      textStyle: { color: t.text },
      tooltip: { trigger: 'axis' },
      legend: { data: runs.map((r) => r.id.slice(0, 8)), textStyle: { color: t.muted } },
      grid: { left: 48, right: 16, top: 30, bottom: 24 },
      xAxis: { type: 'category', boundaryGap: false, axisLabel: { color: t.muted } },
      yAxis: { type: 'value', name: 'normalized', axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.grid } } },
      series: runs.map((r) => {
        const curve = r.data.equityCurve ?? [];
        const base = curve.length ? Number(curve[0].value) : 1;
        return {
          name: r.id.slice(0, 8),
          type: 'line',
          showSymbol: false,
          data: curve.map((p) => (base ? (Number(p.value) / base) * 100 : 0)),
        };
      }),
    }),
    [runs],
  );

  if (runs.length === 0) {
    return (
      <div>
        <PageHeader title="Compare backtests" subtitle="Side-by-side metric matrix and normalized equity curves" />
        <p className="text-ay-muted">Add runs to compare (e.g. /backtests/compare?ids=run1,run2).</p>
      </div>
    );
  }

  return (
    <LoadBeat>
      <PageHeader title="Compare backtests" subtitle="Side-by-side metric matrix and normalized equity curves" />
      {dataHashMismatch && (
        <p className="mb-2 rounded-md bg-warn/15 px-2 py-1 text-sm text-warn ring-1 ring-warn/40">
          Runs have differing dataHash — not a like-for-like comparison.
        </p>
      )}
      {universeMismatch && (
        <p className="mb-2 rounded-md bg-warn/15 px-2 py-1 text-sm text-warn ring-1 ring-warn/40">
          Runs were pinned to differing universes — not a like-for-like comparison.
        </p>
      )}

      <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
        <table className="w-full border-collapse text-sm">
          <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
            <tr>
              <th className="px-2 py-2 font-medium">Metric</th>
              {runs.map((r) => (
                <th key={r.id} className="px-2 py-2 text-right font-mono font-medium">
                  {r.id.slice(0, 8)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {matrix.map((row) => (
              <tr key={row.key} className="border-t border-ay-border">
                <td className="px-2 py-2">{row.label}</td>
                {row.cells.map((cell) => (
                  <td
                    key={cell.id}
                    className={cn('px-2 py-2 text-right tabular-nums', cell.best && 'font-bold text-bull')}
                  >
                    {cell.text}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </BeatBlock>

      <BeatBlock className="card shadow-e1 mt-4">
        <EChart makeOption={equityOption} height={320} ariaLabel="Normalized equity curves" />
      </BeatBlock>
    </LoadBeat>
  );
}

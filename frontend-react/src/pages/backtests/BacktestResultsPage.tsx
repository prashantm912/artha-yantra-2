import { useCallback, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { m } from 'motion/react';
import type { EChartsOption } from 'echarts';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  useBacktestFolds,
  useBacktestMonteCarlo,
  useBacktestResults,
  useBacktestTrades,
  type BacktestResults,
  type CurvePoint,
  type TradeRow,
} from '../../api/backtests.ts';
import { exitReasonBreakdown } from './exitReasonBreakdown.ts';

// /backtests/:id (master plan §20 parity, E-11 / E-12.5): metric panel (+ benchmark-relative columns
// when present), scrollable trade table with per-trade contribution drill-down, persisted equity +
// drawdown + benchmark curves (never client-recomputed), the walk-forward fold panel and the Monte
// Carlo tab — all NULL-safe for runs predating Phase 32A. Chart-bearing → lazy route. View-on-chart /
// journal deep-links land with PR-C10 / PR-C9.

interface MetricDef {
  key: string;
  label: string;
  suffix?: string;
  dp?: number;
}
const CORE_METRICS: MetricDef[] = [
  { key: 'totalReturn', label: 'Total return', suffix: '%' },
  { key: 'cagr', label: 'CAGR', suffix: '%' },
  { key: 'sharpe', label: 'Sharpe', dp: 2 },
  { key: 'sortino', label: 'Sortino', dp: 2 },
  { key: 'maxDrawdown', label: 'Max drawdown', suffix: '%' },
  { key: 'winRate', label: 'Win rate', suffix: '%', dp: 1 },
  { key: 'profitFactor', label: 'Profit factor', dp: 2 },
  { key: 'expectancy', label: 'Expectancy (₹)' },
  { key: 'averageTrade', label: 'Avg trade (₹)' },
  { key: 'exposure', label: 'Exposure', suffix: '%', dp: 1 },
  { key: 'tradeCount', label: 'Trades', dp: 0 },
];
const BENCH_METRICS: MetricDef[] = [
  { key: 'alpha', label: 'Alpha', dp: 4 },
  { key: 'beta', label: 'Beta', dp: 3 },
  { key: 'informationRatio', label: 'Information ratio', dp: 3 },
  { key: 'excessCagr', label: 'Excess CAGR', suffix: '%' },
];

const FOLD_METRICS = ['sharpe', 'totalReturn', 'maxDrawdown', 'winRate'];

function metric(r: BacktestResults, m: MetricDef): string {
  const v = r.metrics[m.key];
  if (v == null || Array.isArray(v)) return '—';
  return `${formatDecimal(String(v), m.dp ?? 2)}${m.suffix ?? ''}`;
}

const price = (v: string | null | undefined) => (v == null ? '—' : formatDecimal(v, 2));
const nums = (a: string[] | undefined) => (a ?? []).map(Number);
const curveNums = (c: CurvePoint[] | null | undefined) => (c ?? []).map((p) => Number(p.value));

type Tab = 'overview' | 'trades' | 'folds' | 'mc';

export function BacktestResultsPage() {
  const { id = '' } = useParams();
  const results = useBacktestResults(id);
  const trades = useBacktestTrades(id);
  const folds = useBacktestFolds(id);
  const mc = useBacktestMonteCarlo(id);

  const [tab, setTab] = useState<Tab>('overview');
  const [selected, setSelected] = useState<TradeRow | null>(null);

  const r = results.data;
  const foldRows = folds.data ?? [];
  const mcData = mc.data ?? null;

  const equityOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      textStyle: { color: t.text },
      legend: { data: ['Equity', 'Benchmark', 'Drawdown'], textStyle: { color: t.muted } },
      tooltip: { trigger: 'axis' },
      grid: { left: 56, right: 56, top: 36, bottom: 36 },
      xAxis: {
        type: 'category',
        data: (r?.equityCurve ?? []).map((p) => p.ts.slice(0, 10)),
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      yAxis: [
        { type: 'value', scale: true, axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.grid } } },
        { type: 'value', name: 'DD%', position: 'right', axisLabel: { color: t.muted }, splitLine: { show: false } },
      ],
      series: [
        { name: 'Equity', type: 'line', data: curveNums(r?.equityCurve), showSymbol: false, lineStyle: { color: t.accent }, itemStyle: { color: t.accent } },
        { name: 'Benchmark', type: 'line', data: curveNums(r?.benchmarkCurve), showSymbol: false, lineStyle: { color: t.muted, type: 'dashed' }, itemStyle: { color: t.muted } },
        { name: 'Drawdown', type: 'line', yAxisIndex: 1, data: curveNums(r?.drawdownCurve), showSymbol: false, lineStyle: { color: t.bear }, itemStyle: { color: t.bear }, areaStyle: { opacity: 0.1, color: t.bear } },
      ],
    }),
    [r],
  );

  const mcFanOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      textStyle: { color: t.text },
      tooltip: { trigger: 'axis' },
      legend: { data: ['p5', 'p50', 'p95'], textStyle: { color: t.muted } },
      grid: { left: 56, right: 16, top: 30, bottom: 28 },
      xAxis: { type: 'category', data: (mcData?.equityBands.step ?? []).map(String), name: 'trade #', axisLabel: { color: t.muted } },
      yAxis: { type: 'value', name: 'equity (₹)', axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.grid } } },
      series: [
        { name: 'p95', type: 'line', smooth: true, showSymbol: false, data: nums(mcData?.equityBands.p95), lineStyle: { color: t.bull } },
        { name: 'p50', type: 'line', smooth: true, showSymbol: false, data: nums(mcData?.equityBands.p50), lineStyle: { color: t.accent } },
        { name: 'p5', type: 'line', smooth: true, showSymbol: false, data: nums(mcData?.equityBands.p5), lineStyle: { color: t.bear } },
      ],
    }),
    [mcData],
  );

  const mcDrawdownOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const d = mcData?.drawdownDistribution;
      return {
        textStyle: { color: t.text },
        title: { text: 'Drawdown distribution (%)', textStyle: { fontSize: 12, color: t.text } },
        tooltip: {},
        grid: { left: 44, right: 16, top: 36, bottom: 24 },
        xAxis: { type: 'category', data: ['p5', 'p50', 'p95', 'mean'], axisLabel: { color: t.muted } },
        yAxis: { type: 'value', axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.grid } } },
        series: [{ type: 'bar', itemStyle: { color: t.bear }, data: [Number(d?.p5 ?? 0), Number(d?.p50 ?? 0), Number(d?.p95 ?? 0), Number(d?.mean ?? 0)] }],
      };
    },
    [mcData],
  );

  const tabs: { id: Tab; label: string; show: boolean }[] = [
    { id: 'overview', label: 'Overview', show: true },
    { id: 'trades', label: 'Trades', show: true },
    { id: 'folds', label: 'Folds', show: foldRows.length > 0 },
    { id: 'mc', label: 'Monte Carlo', show: !!mcData },
  ];

  const exitStats = useMemo(() => exitReasonBreakdown(trades.data?.items ?? []), [trades.data]);

  const contributions = useMemo(() => {
    const c = selected?.contributions;
    if (!c || typeof c !== 'object') return [];
    return Object.entries(c).map(([k, v]) => ({ k, v: String(v) }));
  }, [selected]);

  return (
    <LoadBeat>
      <PageHeader title="Backtest results" subtitle="Metrics, equity and drawdown curves, trades, folds and Monte Carlo" />
      <QueryState
        query={results}
        empty={{ title: 'No results.' }}
        errorTitle="Couldn't load backtest results"
        skeleton={<Skeleton variant="chart-block" height={360} />}
      >
        {(r) => (
        <>
      <div role="tablist" className="mb-4 flex gap-1 border-b border-ay-border">
        {tabs.filter((t) => t.show).map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            onClick={() => setTab(t.id)}
            className={cn(
              '-mb-px border-b-2 px-4 py-2 text-sm font-medium',
              tab === t.id ? 'border-accent text-accent' : 'border-transparent text-ay-muted hover:text-ay-text',
            )}
          >
            {t.label}
          </button>
        ))}
      </div>

      <m.div key={tab} initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.15 }}>
      {tab === 'overview' && (
        <>
          <BeatStrip className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
            {CORE_METRICS.map((m) => (
              <BeatItem key={m.key} className="card shadow-e1">
                <div className="text-caption uppercase tracking-wide text-ay-muted">{m.label}</div>
                <div className="nums font-semibold">{metric(r, m)}</div>
              </BeatItem>
            ))}
            {BENCH_METRICS.filter((m) => r.metrics[m.key] != null).map((m) => (
              <BeatItem key={m.key} className="card shadow-e1">
                <div className="text-caption uppercase tracking-wide text-ay-muted">{m.label}</div>
                <div className="nums font-semibold">{metric(r, m)}</div>
              </BeatItem>
            ))}
          </BeatStrip>
          {r.premiumSource === 'SYNTHETIC' && (
            <p className="mb-2 rounded-md bg-warn/15 px-2 py-1 text-sm text-warn ring-1 ring-warn/40">
              Options premiums are SYNTHETIC_B76 (not snapshot-grade).
            </p>
          )}
          <BeatBlock className="card shadow-e1">
            <EChart makeOption={equityOption} height={320} ariaLabel="Equity, benchmark and drawdown curves" />
          </BeatBlock>
          <p className="mt-2 text-xs tabular-nums text-ay-muted">
            dataHash {r.dataHash ?? '—'} · seed {r.seed ?? '—'}
          </p>
        </>
      )}

      {tab === 'trades' && (
        <>
          {exitStats.length > 0 && (
            <div className="mb-3">
              <h3 className="mb-1.5 text-caption uppercase tracking-wide text-ay-muted">
                Exit-reason breakdown <span className="normal-case">(loaded trades)</span>
              </h3>
              <div className="overflow-auto rounded-lg border border-ay-border">
                <table className="w-full border-collapse text-sm" aria-label="Exit-reason breakdown">
                  <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                    <tr>
                      <th scope="col" className="px-2 py-2 font-medium">Reason</th>
                      <th scope="col" className="px-2 py-2 text-right font-medium">Count</th>
                      <th scope="col" className="px-2 py-2 text-right font-medium">Win rate</th>
                      <th scope="col" className="px-2 py-2 text-right font-medium">Total P&L</th>
                      <th scope="col" className="px-2 py-2 text-right font-medium">Avg P&L</th>
                    </tr>
                  </thead>
                  <tbody>
                    {exitStats.map((s) => (
                      <tr key={s.reason} className="border-t border-ay-border">
                        <td className="px-2 py-2">{s.reason}</td>
                        <td className="px-2 py-2 text-right tabular-nums">{s.count}</td>
                        <td className="px-2 py-2 text-right tabular-nums">{formatDecimal(String(s.winRate), 1)}%</td>
                        <td className={cn('px-2 py-2 text-right tabular-nums', isNegative(s.totalPnl) ? 'text-bear' : 'text-bull')}>
                          {price(s.totalPnl)}
                        </td>
                        <td className={cn('px-2 py-2 text-right tabular-nums', isNegative(s.avgPnl) ? 'text-bear' : 'text-bull')}>
                          {price(s.avgPnl)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
          <div className="max-h-[calc(100vh-18rem)] overflow-auto rounded-lg border border-ay-border">
            <table className="w-full border-collapse text-sm">
              <thead className="sticky top-0 bg-surface-1 text-left text-xs uppercase text-ay-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">#</th>
                  <th className="px-2 py-2 font-medium">Side</th>
                  <th className="px-2 py-2 text-right font-medium">Entry</th>
                  <th className="px-2 py-2 text-right font-medium">Exit</th>
                  <th className="px-2 py-2 text-right font-medium">P&L</th>
                  <th className="px-2 py-2 text-right font-medium">%</th>
                  <th className="px-2 py-2 font-medium">Reason</th>
                </tr>
              </thead>
              <tbody>
                {(trades.data?.items ?? []).map((tr) => (
                  <tr
                    key={tr.seq}
                    role="button"
                    tabIndex={0}
                    onClick={() => setSelected(tr)}
                    onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), setSelected(tr))}
                    aria-pressed={selected?.seq === tr.seq}
                    className={cn(
                      'cursor-pointer border-t border-ay-border hover:bg-surface-2 focus:bg-surface-2 focus:outline-none',
                      selected?.seq === tr.seq && 'bg-surface-2',
                    )}
                  >
                    <td className="px-2 py-2 tabular-nums">{tr.seq}</td>
                    <td className="px-2 py-2">
                      <span className={cn('text-xs font-semibold', tr.side === 'LONG' ? 'text-bull' : 'text-bear')}>{tr.side}</span>
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums">{price(tr.entryPrice)}</td>
                    <td className="px-2 py-2 text-right tabular-nums">{price(tr.exitPrice)}</td>
                    <td className={cn('px-2 py-2 text-right tabular-nums', Number(tr.pnl) < 0 ? 'text-bear' : 'text-bull')}>{price(tr.pnl)}</td>
                    <td className="px-2 py-2 text-right tabular-nums">{price(tr.pnlPct)}</td>
                    <td className="px-2 py-2">{tr.exitReason}</td>
                  </tr>
                ))}
                {(trades.data?.items ?? []).length === 0 && (
                  <tr>
                    <td colSpan={7} className="px-2 py-6 text-center text-ay-muted">No trades.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          {selected && (
            <div className="mt-2 rounded-lg border border-ay-border bg-surface-1 p-3 text-sm">
              <strong>Trade #{selected.seq}</strong> — entry {selected.entryTs.slice(0, 16)} · touch{' '}
              {selected.touchBasis ?? '—'} · stop {price(selected.stopLoss)} · target {price(selected.takeProfit)}
              <Link to={`/charts?runId=${id}&tradeId=${selected.seq}`} className="ml-2 text-xs text-accent hover:underline">
                📈 View on chart
              </Link>
              {contributions.length > 0 && (
                <div className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 tabular-nums">
                  {contributions.map((c) => (
                    <span key={c.k} className="contents">
                      <span className="text-ay-muted">{c.k}</span>
                      <span>{c.v}</span>
                    </span>
                  ))}
                </div>
              )}
            </div>
          )}
        </>
      )}

      {tab === 'folds' && (
        <div className="overflow-auto rounded-lg border border-ay-border">
          <table className="w-full border-collapse text-sm">
            <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
              <tr>
                <th className="px-2 py-2 font-medium">Fold</th>
                <th className="px-2 py-2 font-medium">Test window</th>
                {FOLD_METRICS.map((m) => (
                  <th key={m} className="px-2 py-2 text-right font-medium">
                    OOS {m}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {foldRows.map((f) => (
                <tr key={f.fold.index} className="border-t border-ay-border">
                  <td className="px-2 py-2 tabular-nums">{f.fold.index}</td>
                  <td className="px-2 py-2 tabular-nums">
                    {f.fold.testFrom.slice(0, 10)} → {f.fold.testTo.slice(0, 10)}
                  </td>
                  {FOLD_METRICS.map((m) => (
                    <td key={m} className="px-2 py-2 text-right tabular-nums">
                      {f.oosMetrics[m] != null ? formatDecimal(String(f.oosMetrics[m]), 2) : '—'}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'mc' && mcData && (
        <>
          {mcData.insufficientSample && (
            <p className="mb-2 rounded-md bg-warn/15 px-2 py-1 text-sm text-warn ring-1 ring-warn/40">
              Insufficient sample (&lt; 30 trades) — extend the window.
            </p>
          )}
          <BeatBlock className="card shadow-e1 mb-3">
            <EChart makeOption={mcFanOption} height={280} ariaLabel="Monte Carlo equity-band fan" />
          </BeatBlock>
          <BeatBlock className="card shadow-e1">
            <EChart makeOption={mcDrawdownOption} height={240} ariaLabel="Monte Carlo drawdown distribution" />
          </BeatBlock>
          <p className="mt-2 text-xs tabular-nums text-ay-muted">risk of ruin {mcData.riskOfRuin}</p>
        </>
      )}
      </m.div>
        </>
        )}
      </QueryState>
    </LoadBeat>
  );
}

import { useCallback, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { m } from 'motion/react';
import type { EChartsOption } from 'echarts';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { Button } from '../../components/atoms/Button.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  useBacktestFolds,
  useBacktestMonteCarlo,
  useBacktestResults,
  useBacktestTrades,
  useOiAttribution,
  type BacktestResults,
  type CurvePoint,
  type FoldRow,
  type OiAttributionBucket,
  type OiAttributionTradeRow,
  type TradeRow,
} from '../../api/backtests.ts';
import { useStrategies } from '../../api/strategies.ts';
import {
  exportEquity,
  exportFolds,
  exportTrades,
  type ExportFormat,
} from '../../api/backtestExport.ts';
import { exitReasonBreakdown, type ExitReasonStat } from './exitReasonBreakdown.ts';

const OI_INTERVALS = ['3m', '5m', '10m', '15m'] as const;

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

/** Test-window length in days (from the equity curve extents); null when unknowable. */
function windowDays(r: BacktestResults): number | null {
  const c = r.equityCurve;
  if (!c || c.length < 2) return null;
  const ms = Date.parse(c[c.length - 1].ts) - Date.parse(c[0].ts);
  return Number.isFinite(ms) ? ms / 86_400_000 : null;
}

function metric(r: BacktestResults, m: MetricDef): string {
  const v = r.metrics[m.key];
  if (v == null || Array.isArray(v)) return '—';
  // Annualizing a weeks-long window prints decision noise ("CAGR −90.67%" off 6 weeks —
  // audit 2026-07-02 §5); suppress CAGR-class metrics under 90 days.
  if ((m.key === 'cagr' || m.key === 'excessCagr')) {
    const days = windowDays(r);
    if (days != null && days < 90) return 'n/a (<90d)';
  }
  return `${formatDecimal(String(v), m.dp ?? 2)}${m.suffix ?? ''}`;
}

const price = (v: string | null | undefined) => (v == null ? '—' : formatDecimal(v, 2));
const nums = (a: string[] | undefined) => (a ?? []).map(Number);
const curveNums = (c: CurvePoint[] | null | undefined) => (c ?? []).map((p) => Number(p.value));

// Shared DataTable columns for the three tabular archetypes on this page (exit-reason breakdown,
// walk-forward folds, OI-confluence buckets). Same values/order/formatting as the hand-rolled tables
// they replaced — DataTable adds zebra + sticky header + a mobile card list (§3.2, GraduationPage
// pattern). The interactive per-trade table keeps its bespoke row-select markup (not a DataTable fit).
const EXIT_COLUMNS: DataColumn<ExitReasonStat>[] = [
  { id: 'reason', header: 'Reason', align: 'left', mobileLabel: 'Reason', render: (s) => s.reason },
  { id: 'count', header: 'Count', align: 'right', mobileLabel: 'Count', render: (s) => s.count },
  { id: 'winRate', header: 'Win rate', align: 'right', mobileLabel: 'Win rate', render: (s) => `${formatDecimal(String(s.winRate), 1)}%` },
  { id: 'totalPnl', header: 'Total P&L', align: 'right', mobileLabel: 'Total P&L', cellClassName: (s) => (isNegative(s.totalPnl) ? 'text-bear' : 'text-bull'), render: (s) => price(s.totalPnl) },
  { id: 'avgPnl', header: 'Avg P&L', align: 'right', mobileLabel: 'Avg P&L', cellClassName: (s) => (isNegative(s.avgPnl) ? 'text-bear' : 'text-bull'), render: (s) => price(s.avgPnl) },
];

const FOLD_COLUMNS: DataColumn<FoldRow>[] = [
  { id: 'fold', header: 'Fold', align: 'left', mono: true, mobileLabel: 'Fold', render: (f) => f.fold.index },
  { id: 'window', header: 'Test window', align: 'left', mono: true, mobileLabel: 'Test window', render: (f) => `${f.fold.testFrom.slice(0, 10)} → ${f.fold.testTo.slice(0, 10)}` },
  ...FOLD_METRICS.map((mk): DataColumn<FoldRow> => ({
    id: `oos-${mk}`,
    header: `OOS ${mk}`,
    align: 'right',
    mobileLabel: `OOS ${mk}`,
    render: (f) => (f.oosMetrics[mk] != null ? formatDecimal(String(f.oosMetrics[mk]), 2) : '—'),
  })),
];

const OI_COLUMNS: DataColumn<OiAttributionBucket>[] = [
  { id: 'label', header: 'Entry confluence', align: 'left', mobileLabel: 'Entry confluence', render: (b) => b.label },
  { id: 'count', header: 'Trades', align: 'right', mobileLabel: 'Trades', render: (b) => b.count },
  { id: 'wins', header: 'Wins', align: 'right', mobileLabel: 'Wins', render: (b) => b.wins },
  { id: 'winRate', header: 'Win rate', align: 'right', mobileLabel: 'Win rate', render: (b) => (b.winRate == null ? '—' : formatDecimal(String(b.winRate), 3)) },
  { id: 'totalPnl', header: 'Total P&L', align: 'right', mobileLabel: 'Total P&L', cellClassName: (b) => (isNegative(b.totalPnl) ? 'text-bear' : 'text-bull'), render: (b) => price(b.totalPnl) },
  { id: 'avgPnl', header: 'Avg P&L', align: 'right', mobileLabel: 'Avg P&L', cellClassName: (b) => (isNegative(b.avgPnl ?? '0') ? 'text-bear' : 'text-bull'), render: (b) => price(b.avgPnl) },
];

type Tab = 'overview' | 'trades' | 'folds' | 'mc' | 'oi';

function ExportControls({
  artifact,
  onExport,
}: {
  artifact: 'equity' | 'trades' | 'folds';
  onExport: (format: ExportFormat) => Promise<void>;
}) {
  return (
    <div role="group" aria-label={`Download ${artifact}`} className="flex items-center gap-1">
      <span className="mr-1 text-caption uppercase tracking-wide text-ay-muted">Export</span>
      {(['csv', 'json'] as const).map((format) => (
        <Button
          key={format}
          type="button"
          variant="outline"
          size="sm"
          onClick={() => void onExport(format)}
        >
          <span className="sr-only">Download {artifact} as {format.toUpperCase()}</span>
          <span aria-hidden>{format.toUpperCase()}</span>
        </Button>
      ))}
    </div>
  );
}

export function BacktestResultsPage() {
  const { id = '' } = useParams();
  const results = useBacktestResults(id);
  const strategies = useStrategies('', null);
  const trades = useBacktestTrades(id);
  const folds = useBacktestFolds(id);
  const mc = useBacktestMonteCarlo(id);

  const [tab, setTab] = useState<Tab>('overview');
  const [selected, setSelected] = useState<TradeRow | null>(null);
  const [oiInterval, setOiInterval] = useState<string>('5m');
  const oi = useOiAttribution(id, oiInterval);

  // Per-trade OI-confluence verdict (Connecting-Dots trend at entry), joined to each trade by seq.
  // Empty/“—” for trades with no captured OI at entry (e.g. derived history, where OI is muted).
  const oiBySeq = useMemo(() => {
    const map = new Map<number, OiAttributionTradeRow>();
    for (const t of oi.data?.trades ?? []) map.set(t.seq, t);
    return map;
  }, [oi.data]);

  const r = results.data;
  const foldRows = folds.data ?? [];
  const mcData = mc.data ?? null;

  // Header context: the originating strategy name (mapped from its id, schema-boundary intact) + the
  // run date. Falls back to the static blurb until results load.
  const headerSubtitle = useMemo(() => {
    if (!r) return 'Metrics, equity and drawdown curves, trades, folds and Monte Carlo';
    const name = r.strategyId
      ? ((strategies.data?.items ?? []).find((s) => s.id === r.strategyId)?.name ??
         `deleted strategy · ${r.strategyId.slice(0, 8)}`)
      : 'Strategy';
    const ranAt = r.ranAt ? ` · ran ${r.ranAt.slice(0, 19).replace('T', ' ')}` : '';
    return `${name}${ranAt}`;
  }, [r, strategies.data]);

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
        // one label per DAY: the curve carries many points per session, so per-tick labels
        // repeated the same date across the axis (audit 2026-07-02 §5)
        axisLabel: {
          color: t.muted,
          interval: (i: number, value: string) => {
            const dates = (r?.equityCurve ?? []).map((p) => p.ts.slice(0, 10));
            return i === 0 || dates[i - 1] !== value;
          },
        },
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

  const oiData = oi.data ?? null;
  const oiWinRateOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const scored = (oiData?.buckets ?? []).filter((b) => b.count > 0);
      return {
        textStyle: { color: t.text },
        tooltip: { trigger: 'axis' },
        grid: { left: 44, right: 16, top: 24, bottom: 40 },
        xAxis: { type: 'category', data: scored.map((b) => b.label), axisLabel: { color: t.muted, interval: 0, rotate: 20 } },
        yAxis: { type: 'value', name: 'win %', max: 100, axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.grid } } },
        series: [
          {
            type: 'bar',
            data: scored.map((b) => ({
              value: b.winRate == null ? 0 : Math.round(Number(b.winRate) * 1000) / 10,
              // trend 1 Ext.Bullish / 2 Bullish → bull tone; 3 Bearish / 4 Ext.Bearish → bear; NO_DATA muted.
              itemStyle: { color: b.trend < 0 ? t.muted : b.trend <= 2 ? t.bull : t.bear },
            })),
          },
        ],
      };
    },
    [oiData],
  );

  const tabs: { id: Tab; label: string; show: boolean }[] = [
    { id: 'overview', label: 'Overview', show: true },
    { id: 'trades', label: 'Trades', show: true },
    // Show the tab when there ARE folds, OR when the fetch errored — so a fold-fetch failure surfaces
    // as an error state inside the tab instead of silently hiding it (a false "no walk-forward"). A
    // genuine no-walk-forward run (200 []) keeps the tab hidden, as before.
    { id: 'folds', label: 'Folds', show: foldRows.length > 0 || folds.isError },
    { id: 'mc', label: 'Monte Carlo', show: !!mcData },
    { id: 'oi', label: 'OI Attribution', show: true },
  ];

  const exitStats = useMemo(() => exitReasonBreakdown(trades.data?.items ?? []), [trades.data]);

  const contributions = useMemo(() => {
    const c = selected?.contributions;
    if (!c || typeof c !== 'object') return [];
    return Object.entries(c).map(([k, v]) => ({ k, v: String(v) }));
  }, [selected]);

  // Per-trade table columns. A useMemo (not a module const) because the OI-confluence and #-selector
  // cells close over page state (oiBySeq / oiInterval / the selected seq). Same values/order/formatting
  // as the hand-rolled table it replaced — DataTable adds zebra + sticky header + a mobile card list.
  const perTradeColumns = useMemo<DataColumn<TradeRow>[]>(
    () => [
      {
        id: 'seq',
        header: '#',
        align: 'left',
        mono: true,
        mobileLabel: '#',
        // Audit M23: the row keeps its table `row` semantics (a `role="button"` on a <tr> destroys the
        // grid for AT). The keyboard/AT activator is this real in-cell toggle button; the DataTable
        // onRowClick stays a mouse-only convenience.
        render: (tr) => (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              setSelected(tr);
            }}
            aria-pressed={selected?.seq === tr.seq}
            aria-label={`Show details for trade ${tr.seq}`}
            className="rounded tabular-nums focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
          >
            {tr.seq}
          </button>
        ),
      },
      {
        id: 'side',
        header: 'Side',
        align: 'left',
        mobileLabel: 'Side',
        // real trades carry side=BUY (option legs) — the old ==='LONG' check never matched, so every
        // BUY rendered bear-red (audit 2026-07-02 §3)
        render: (tr) => (
          <span className={cn('text-xs font-semibold', tr.side === 'LONG' || tr.side === 'BUY' ? 'text-bull' : 'text-bear')}>
            {tr.side}
          </span>
        ),
      },
      {
        id: 'time',
        header: 'Time',
        align: 'left',
        mono: true,
        mobileLabel: 'Time',
        cellClassName: () => 'text-ay-muted',
        render: (tr) => tr.entryTs.slice(5, 16).replace('T', ' '),
      },
      { id: 'entry', header: 'Entry', align: 'right', mobileLabel: 'Entry', render: (tr) => price(tr.entryPrice) },
      { id: 'exit', header: 'Exit', align: 'right', mobileLabel: 'Exit', render: (tr) => price(tr.exitPrice) },
      {
        id: 'pnl',
        header: 'P&L',
        align: 'right',
        mobileLabel: 'P&L',
        cellClassName: (tr) => (Number(tr.pnl) < 0 ? 'text-bear' : 'text-bull'),
        render: (tr) => price(tr.pnl),
      },
      { id: 'pnlPct', header: '%', align: 'right', mobileLabel: '%', render: (tr) => price(tr.pnlPct) },
      {
        id: 'oiConf',
        header: 'OI Conf.',
        align: 'left',
        mobileLabel: 'OI Conf.',
        render: (tr) => {
          const a = oiBySeq.get(tr.seq);
          const oiTone =
            a == null ? 'text-ay-muted' : a.trend > 0 ? 'text-bull' : a.trend < 0 ? 'text-bear' : 'text-ay-muted';
          return (
            <span
              className={cn('text-xs font-medium', oiTone)}
              title={a ? `Connecting-Dots trend at entry (${oiInterval})` : 'No captured OI at entry'}
            >
              {a?.trendLabel ?? '—'}
            </span>
          );
        },
      },
      { id: 'reason', header: 'Reason', align: 'left', mobileLabel: 'Reason', render: (tr) => tr.exitReason },
    ],
    [oiBySeq, oiInterval, selected?.seq],
  );

  return (
    <LoadBeat>
      <PageHeader title="Backtest results" subtitle={headerSubtitle} help="Full report for one backtest run — headline metrics, equity and drawdown curves, every trade, walk-forward folds, Monte Carlo, and OI-confluence attribution." />
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
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-h3">Equity curve</h2>
              <ExportControls artifact="equity" onExport={(format) => exportEquity(id, format)} />
            </div>
            <EChart makeOption={equityOption} height={320} ariaLabel="Equity, benchmark and drawdown curves" />
          </BeatBlock>
          <p className="mt-2 text-xs tabular-nums text-ay-muted">
            dataHash {r.dataHash ?? '—'} · seed {r.seed ?? '—'}
            {r.engineSha && (
              <>
                {' · '}engine{' '}
                <span title={r.engineImage ? `${r.engineSha} (${r.engineImage})` : r.engineSha}>
                  {r.engineSha.slice(0, 12)}
                </span>
              </>
            )}
          </p>
        </>
      )}

      {tab === 'trades' && (
        <>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-h3">Trades</h2>
            <ExportControls artifact="trades" onExport={(format) => exportTrades(id, format)} />
          </div>
          {exitStats.length > 0 && (
            <div className="mb-3">
              <h3 className="mb-1.5 text-caption uppercase tracking-wide text-ay-muted">
                Exit-reason breakdown <span className="normal-case">(loaded trades)</span>
              </h3>
              <DataTable
                columns={EXIT_COLUMNS}
                rows={exitStats}
                rowKey={(s) => s.reason}
                ariaLabel="Exit-reason breakdown"
              />
            </div>
          )}
          <DataTable
            columns={perTradeColumns}
            rows={trades.data?.items ?? []}
            rowKey={(tr) => String(tr.seq)}
            ariaLabel="Trades"
            onRowClick={(tr) => setSelected(tr)}
            rowClassName={(tr) => (selected?.seq === tr.seq ? 'bg-surface-2' : '')}
            maxHeight="calc(100vh - 18rem)"
            emptyMessage="No trades."
          />
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
        <>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-h3">Walk-forward folds</h2>
          <ExportControls artifact="folds" onExport={(format) => exportFolds(id, format)} />
        </div>
        <QueryState
          query={folds}
          empty={{ title: 'No walk-forward folds for this run.' }}
          errorTitle="Couldn't load walk-forward folds"
          skeleton={<Skeleton variant="table-rows" rows={4} cols={6} className="rounded-lg border border-ay-border p-2" />}
        >
          {(rows) => (
            <DataTable
              columns={FOLD_COLUMNS}
              rows={rows}
              rowKey={(f) => String(f.fold.index)}
              ariaLabel="Walk-forward folds"
            />
          )}
        </QueryState>
        </>
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

      {tab === 'oi' && (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2 text-sm text-ay-muted">
              OI interval
              <select
                value={oiInterval}
                onChange={(e) => setOiInterval(e.target.value)}
                aria-label="OI attribution interval"
                title="Candle interval used to read the OI-confluence trend at each trade's entry."
                className="rounded-md border border-ay-border bg-surface-1 px-2 py-1 text-sm text-ay-text"
              >
                {OI_INTERVALS.map((iv) => (
                  <option key={iv} value={iv}>{iv}</option>
                ))}
              </select>
            </label>
            {oiData?.underlying && (
              <span className="text-sm text-ay-muted">
                {oiData.underlying} · {oiData.tradesAttributed}/{oiData.tradeCount} trades scored ·{' '}
                {oiData.sessionsCovered} sessions covered, {oiData.sessionsUncovered} uncovered
              </span>
            )}
          </div>
          <QueryState
            query={oi}
            empty={{ title: 'No attribution.' }}
            errorTitle="Couldn't load OI attribution"
            skeleton={<Skeleton variant="chart-block" height={280} />}
          >
            {(d) =>
              d.tradeCount === 0 ? (
                <p className="rounded-md border border-ay-border bg-surface-1 px-3 py-6 text-center text-sm text-ay-muted">
                  {d.caveat ?? 'No trades to attribute.'}
                </p>
              ) : (
                <>
                  {d.oiDerived && (
                    <p className="mb-2 inline-block rounded-md bg-accent/15 px-2 py-1 text-xs text-accent ring-1 ring-accent/40">
                      OI reconstructed from per-contract candles (no captured snapshots this period)
                    </p>
                  )}
                  {d.caveat && (
                    <p className="mb-3 rounded-md bg-warn/15 px-2 py-1 text-xs text-warn ring-1 ring-warn/40">
                      {d.caveat}
                    </p>
                  )}
                  <BeatBlock className="card shadow-e1 mb-3">
                    <EChart
                      makeOption={oiWinRateOption}
                      height={240}
                      ariaLabel="Win rate by entry OI-confluence trend"
                    />
                  </BeatBlock>
                  <DataTable
                    columns={OI_COLUMNS}
                    rows={d.buckets.filter((b) => b.count > 0)}
                    rowKey={(b) => b.label}
                    ariaLabel="OI-confluence attribution buckets"
                  />
                </>
              )
            }
          </QueryState>
        </>
      )}
      </m.div>
        </>
        )}
      </QueryState>
    </LoadBeat>
  );
}

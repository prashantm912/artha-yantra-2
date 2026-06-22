import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { useFiiDerivativeStats } from '../../api/oiAnalytics.ts';
import { foldFiiDerivativeStats, type FiiDerivativeStatsRow } from '../../api/fiiDerivativeStatsFold.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';

// FII Derivative Stats (oipulse §fii-dii/fii-derivative-stats): FII net activity across the four F&O
// segments (Index/Stock × Futures/Options), daily, ₹ Crore. One net-value bar chart per segment (own
// y-scale — magnitudes differ by an order of magnitude) + the detailed table. Upstox-sourced.

const isoDaysAgo = (days: number): string => {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
};

const n = (s: string | null): number => (s == null ? 0 : Number(s));

const SEGMENTS: { label: string; pick: (r: FiiDerivativeStatsRow) => string | null }[] = [
  { label: 'Index Futures', pick: (r) => r.idxFut },
  { label: 'Index Options', pick: (r) => r.idxOpt },
  { label: 'Stock Futures', pick: (r) => r.stkFut },
  { label: 'Stock Options', pick: (r) => r.stkOpt },
];

export function FiiDerivativeStatsPage() {
  const to = useMemo(() => isoDaysAgo(0), []);
  const from = useMemo(() => isoDaysAgo(420), []);
  const q = useFiiDerivativeStats(from, to);

  const rows = useMemo(() => foldFiiDerivativeStats(q.data ?? []), [q.data]);
  const asc = useMemo(() => [...rows].reverse(), [rows]); // chart reads oldest→newest

  const segmentOption = useCallback(
    (pick: (r: FiiDerivativeStatsRow) => string | null) =>
      (t: ChartTheme): EChartsOption => ({
        aria: { enabled: true },
        textStyle: { color: t.text },
        grid: { left: 64, right: 16, top: 16, bottom: 48 },
        tooltip: {
          trigger: 'axis',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          axisPointer: { type: 'shadow' },
        },
        xAxis: {
          type: 'category',
          data: asc.map((r) => r.tradeDate),
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted },
        },
        yAxis: {
          type: 'value',
          name: '₹ Cr',
          scale: true,
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted },
        },
        dataZoom: [
          { type: 'inside', start: 0, end: 100 },
          { type: 'slider', start: 0, end: 100, height: 16, bottom: 20, borderColor: t.border, textStyle: { color: t.muted } },
        ],
        series: [
          {
            type: 'bar',
            data: asc.map((r) => {
              const v = n(pick(r));
              return { value: v, itemStyle: { color: v >= 0 ? t.bull : t.bear } };
            }),
          },
        ],
      }),
    [asc],
  );

  const options = useMemo(() => SEGMENTS.map((s) => segmentOption(s.pick)), [segmentOption]);

  // oipulse: read-only table (no column sort) — Date + the four segment NET values.
  const columns: DataColumn<FiiDerivativeStatsRow>[] = [
    { id: 'date', header: 'Date', align: 'left', render: (r) => r.tradeDate, mobileLabel: 'Date' },
    { id: 'idxFut', header: 'Index Futures', render: (r) => <ValueDeltaCell value={r.idxFut} />, mobileLabel: 'Index Fut' },
    { id: 'idxOpt', header: 'Index Options', render: (r) => <ValueDeltaCell value={r.idxOpt} />, mobileLabel: 'Index Opt' },
    { id: 'stkFut', header: 'Stock Futures', render: (r) => <ValueDeltaCell value={r.stkFut} />, mobileLabel: 'Stock Fut' },
    { id: 'stkOpt', header: 'Stock Options', render: (r) => <ValueDeltaCell value={r.stkOpt} />, mobileLabel: 'Stock Opt' },
  ];

  return (
    <div>
      <h1 className="mb-2 text-base font-semibold text-ay-text">FII Derivative Stats</h1>
      <p className="mb-3 text-xs text-ay-muted">
        FII net activity across the four F&amp;O segments · Values in ₹ Crore · green = net long, red = net short
      </p>

      {asc.length > 0 && (
        <div className="mb-4 grid grid-cols-1 gap-4 md:grid-cols-2">
          {SEGMENTS.map((s, i) => (
            <div key={s.label}>
              <h2 className="mb-1 text-center text-sm font-semibold text-ay-text">{s.label}</h2>
              <EChart makeOption={options[i]} height={220} ariaLabel={`FII net ${s.label} per day`} />
            </div>
          ))}
        </div>
      )}

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(r) => r.tradeDate}
        pageSize={25}
        ariaLabel="Detailed FII derivative segment net activity"
        emptyMessage="No FII derivative stats for this window."
      />
    </div>
  );
}

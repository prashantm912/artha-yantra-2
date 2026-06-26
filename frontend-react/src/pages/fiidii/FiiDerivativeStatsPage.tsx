import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { useFiiDerivativeStats } from '../../api/oiAnalytics.ts';
import { foldFiiDerivativeStats, type FiiDerivativeStatsRow } from '../../api/fiiDerivativeStatsFold.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

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
    { id: 'date', header: 'Date', align: 'left', render: (r) => r.tradeDate, mobileLabel: 'Date', help: 'The trading session this row of FII derivative activity is for.' },
    { id: 'idxFut', header: 'Index Futures', render: (r) => <ValueDeltaCell value={r.idxFut} />, mobileLabel: 'Index Fut', help: 'FII net position in index futures — positive = net long (bullish), negative = net short (₹ Crore).' },
    { id: 'idxOpt', header: 'Index Options', render: (r) => <ValueDeltaCell value={r.idxOpt} />, mobileLabel: 'Index Opt', help: 'FII net position in index options — positive = net long, negative = net short (₹ Crore).' },
    { id: 'stkFut', header: 'Stock Futures', render: (r) => <ValueDeltaCell value={r.stkFut} />, mobileLabel: 'Stock Fut', help: 'FII net position in single-stock futures — positive = net long, negative = net short (₹ Crore).' },
    { id: 'stkOpt', header: 'Stock Options', render: (r) => <ValueDeltaCell value={r.stkOpt} />, mobileLabel: 'Stock Opt', help: 'FII net position in single-stock options — positive = net long, negative = net short (₹ Crore).' },
  ];

  return (
    <LoadBeat>
      <PageHeader
        title="FII Derivative Stats"
        subtitle="FII net activity across the four F&O segments · Values in ₹ Crore · green = net long, red = net short"
        help="Shows daily FII net positioning across the four F&O segments (index/stock × futures/options) in ₹ Crore — net long is bullish, net short is bearish."
      />

      <QueryState
        query={q}
        isEmpty={() => rows.length === 0}
        empty={{ title: 'No FII derivative stats for this window.' }}
        errorTitle="Couldn't load FII derivative stats"
        skeleton={
          <div className="space-y-4">
            <Skeleton variant="chart-block" height={220} />
            <Skeleton variant="table-rows" rows={6} cols={5} />
          </div>
        }
      >
        {() => (
          <>
            {asc.length > 0 && (
              <BeatBlock className="mb-4 grid grid-cols-1 gap-4 md:grid-cols-2">
                {SEGMENTS.map((s, i) => (
                  <div key={s.label} className="card shadow-e1">
                    <h2 className="mb-1 text-h3 text-ay-text">{s.label}</h2>
                    <EChart makeOption={options[i]} height={220} ariaLabel={`FII net ${s.label} per day`} />
                  </div>
                ))}
              </BeatBlock>
            )}

            <BeatBlock>
              <DataTable
                columns={columns}
                rows={rows}
                rowKey={(r) => r.tradeDate}
                pageSize={25}
                ariaLabel="Detailed FII derivative segment net activity"
                emptyMessage="No FII derivative stats for this window."
              />
            </BeatBlock>
          </>
        )}
      </QueryState>
    </LoadBeat>
  );
}

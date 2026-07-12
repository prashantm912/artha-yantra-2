import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { TrendingDown } from 'lucide-react';
import { usePremiumSeries } from '../../api/oiAnalytics.ts';
import { formatDecimal } from '../../lib/decimal.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// Option Premium Decay (audit §6.9 / §2.4-2, the /premium-series feed): the ATM straddle premium per
// interval bucket across the session — theta bleeding the straddle down while the underlying (a muted
// second axis for context) drifts. One captured snapshot per bucket; the ATM re-picks per bucket (the
// listed strike nearest that bucket's spot), so a mid-session ATM roll shows as a small step. Money
// stays a decimal string except where it crosses to a chart coordinate. Lazy-loaded (ECharts bundle).

const n = (s: string | null): number | null => (s == null ? null : Number(s));

/** "HH:MM" IST from a +05:30 bucket ISO (the wire already carries the IST offset — slice, don't shift). */
const clock = (iso: string): string => iso.slice(11, 16);

export function PremiumDecayPage() {
  const q = usePremiumSeries();
  const series = q.data ?? null;
  const items = useMemo(() => series?.items ?? [], [series]);
  const latest = items.length > 0 ? items[items.length - 1] : null;

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      legend: { top: 0, textStyle: { color: t.muted }, data: ['ATM straddle', 'Spot'] },
      grid: { left: 56, right: 56, top: 36, bottom: 48 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
        valueFormatter: (v: unknown) => (v == null ? '—' : Number(v).toFixed(2)),
      },
      xAxis: {
        type: 'category',
        data: items.map((p) => clock(p.bucket)),
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      yAxis: [
        {
          type: 'value',
          name: 'Straddle',
          scale: true,
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted },
        },
        {
          type: 'value',
          name: 'Spot',
          scale: true,
          splitLine: { show: false },
          axisLabel: { color: t.muted },
        },
      ],
      graphic: [
        {
          type: 'text',
          right: 56,
          bottom: 36,
          style: { text: 'ArthaYantra', fill: t.muted, fontSize: 11, opacity: 0.5 },
        },
      ],
      series: [
        {
          name: 'ATM straddle',
          type: 'line',
          smooth: true,
          showSymbol: false,
          yAxisIndex: 0,
          data: items.map((p) => n(p.atmStraddle)),
          lineStyle: { color: t.accent, width: 2 },
          itemStyle: { color: t.accent },
          areaStyle: { color: t.accent, opacity: 0.08 },
        },
        {
          name: 'Spot',
          type: 'line',
          smooth: true,
          showSymbol: false,
          yAxisIndex: 1,
          data: items.map((p) => n(p.spot)),
          lineStyle: { color: t.muted, width: 1, type: 'dashed' },
          itemStyle: { color: t.muted },
        },
      ],
    }),
    [items],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="Premium Decay"
        help="Tracks the at-the-money straddle premium through the session — the combined call + put price a straddle buyer pays. A steady slide with a flat underlying is theta (time decay) at work; a sharp jump marks a volatility or directional expansion."
        subtitle="ATM straddle premium per interval bucket — the intraday decay curve"
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <BeatStrip className="card shadow-e1 mb-3 flex flex-wrap items-center gap-2 text-sm text-ay-muted">
        {latest?.atmStrike && (
          <BeatItem>
            <Metric label="ATM" value={latest.atmStrike} />
          </BeatItem>
        )}
        {latest?.atmStraddle && (
          <BeatItem>
            <Metric label="ATM straddle" value={formatDecimal(latest.atmStraddle, 2)} />
          </BeatItem>
        )}
        {latest?.spot && (
          <BeatItem>
            <Metric label="Spot" value={formatDecimal(latest.spot, 2)} />
          </BeatItem>
        )}
        <BeatItem>
          <Metric label="Buckets" value={String(items.length)} />
        </BeatItem>
        <span className="text-xs">· straddle solid (left axis) · spot dashed (right axis)</span>
      </BeatStrip>

      <QueryState
        query={q}
        isEmpty={() => items.length === 0}
        empty={{
          icon: TrendingDown,
          title: 'No premium series — pick an underlying + expiry with captured snapshot buckets.',
        }}
        errorTitle="Couldn't load the premium decay"
        skeleton={<Skeleton variant="chart-block" className="h-64 sm:h-80 lg:h-[440px]" />}
      >
        {() => (
          <BeatBlock className="card shadow-e1">
            <h2 className="mb-2 text-h3 text-ay-text">ATM straddle over the session</h2>
            <EChart
              makeOption={makeOption}
              className="h-64 sm:h-80 lg:h-[440px]"
              ariaLabel="ATM straddle premium and underlying spot over the session"
            />
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

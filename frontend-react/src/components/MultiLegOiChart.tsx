import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import type { MultiOi } from '../api/types.ts';
import { toMultiLegSeries } from '../core/multiLegOiSeries.ts';
import { legHsl } from '../core/legColors.ts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';

// oipulse "Multiple OI Chart" (§options/multiple-oi-chart): a dual-axis multi-line chart — the underlying
// PRICE (left axis, blue DOTTED reference line) + one coloured OI line per selected leg (right axis), CE
// and PE freely mixed. Series maths in core/multiLegOiSeries; this only shapes the ECharts option from the
// live --ay-* theme tokens. NOTE: the live page is socket-driven in oipulse; we are REST/poll like every
// other AY OI page (disclosed divergence).

const compactInt = (n: number) => new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(n);

interface MultiLegOiChartProps {
  data: MultiOi | null;
}

export function MultiLegOiChart({ data }: MultiLegOiChartProps) {
  const series = useMemo(() => toMultiLegSeries(data), [data]);

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      // accent is reserved for the price line, so OI lines start at bull/bear/warn then golden-angle HSL.
      const legColor = (i: number): string => (i < 3 ? [t.bull, t.bear, t.warn][i] : legHsl(i));
      const oiSeries = series.legs.map((l, i) => ({
        name: l.label,
        type: 'line' as const,
        yAxisIndex: 1,
        showSymbol: false,
        connectNulls: true,
        data: l.oi,
        lineStyle: { color: legColor(i), width: 1.75 },
        itemStyle: { color: legColor(i) },
      }));

      return {
        aria: { enabled: true },
        textStyle: { color: t.text },
        legend: { top: 0, textStyle: { color: t.muted }, data: ['Price', ...series.legs.map((l) => l.label)] },
        grid: { left: 56, right: 56, top: 36, bottom: 64 },
        tooltip: {
          trigger: 'axis',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          axisPointer: { type: 'cross', lineStyle: { color: t.crosshair } },
        },
        toolbox: {
          right: 12,
          iconStyle: { borderColor: t.muted },
          feature: { dataZoom: { yAxisIndex: 'none' }, restore: {}, saveAsImage: { backgroundColor: t.surface1 } },
        },
        xAxis: {
          type: 'category',
          data: series.times,
          boundaryGap: false,
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted },
        },
        // Left = underlying price, Right = OI (the multi-line); independent scaling.
        yAxis: [
          {
            type: 'value',
            name: 'Price',
            scale: true,
            position: 'left',
            splitLine: { lineStyle: { color: t.grid } },
            axisLabel: { color: t.muted },
            nameTextStyle: { color: t.muted },
          },
          {
            type: 'value',
            name: 'OI',
            scale: true,
            position: 'right',
            splitLine: { show: false },
            axisLabel: { color: t.muted, formatter: (v: number) => compactInt(v) },
            nameTextStyle: { color: t.muted },
          },
        ],
        dataZoom: [
          { type: 'inside', start: 0, end: 100 },
          { type: 'slider', start: 0, end: 100, height: 20, bottom: 28, borderColor: t.border, textStyle: { color: t.muted } },
        ],
        series: [
          {
            name: 'Price',
            type: 'line',
            yAxisIndex: 0,
            showSymbol: false,
            connectNulls: true,
            data: series.price,
            // DOTTED underlying (oipulse) — a deliberate divergence from the repo's dashed reference lines.
            lineStyle: { color: t.accent, type: 'dotted', width: 1.5 },
            itemStyle: { color: t.accent },
          },
          ...oiSeries,
        ],
      };
    },
    [series],
  );

  return (
    <EChart
      makeOption={makeOption}
      className="h-64 sm:h-80 lg:h-[440px]"
      ariaLabel="Multiple OI chart: underlying price with one open-interest line per selected option leg"
    />
  );
}

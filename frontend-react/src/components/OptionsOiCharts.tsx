import { useCallback } from 'react';
import type { EChartsOption } from 'echarts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';
import type { OptionsOiChartViz } from '../core/optionsOiChartFold.ts';

// The three oipulse "Options OI Chart" line charts (§options/oi-chart), off one strike's CE+PE series:
//   1. Call-vs-Put OI  — Call OI (green) + Put OI (red) on a single OI axis.
//   2. Call OI vs Call premium — dual-axis: Call OI (green, left) + Call price (orange, right).
//   3. Put  OI vs Put  premium — dual-axis: Put  OI (red,   left) + Put  price (orange, right).
// Maths are pre-folded in core/optionsOiChartFold; these only shape the ECharts option from the live tokens.

const compactInt = (n: number) => new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(n);

function base(times: string[], t: ChartTheme): EChartsOption {
  return {
    aria: { enabled: true },
    textStyle: { color: t.text },
    tooltip: {
      trigger: 'axis',
      backgroundColor: t.surface1,
      borderColor: t.border,
      textStyle: { color: t.text },
      axisPointer: { type: 'cross', lineStyle: { color: t.crosshair } },
    },
    xAxis: {
      type: 'category',
      data: times,
      axisLine: { lineStyle: { color: t.border } },
      axisLabel: { color: t.muted },
    },
  };
}

/** Call OI (green) vs Put OI (red) on a single OI axis. */
export function CallPutOiChart({ viz }: { viz: OptionsOiChartViz }) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      ...base(viz.times, t),
      legend: { top: 0, textStyle: { color: t.muted }, data: ['Call OI', 'Put OI'] },
      grid: { left: 56, right: 16, top: 36, bottom: 28 },
      yAxis: {
        type: 'value',
        scale: true,
        splitLine: { lineStyle: { color: t.grid } },
        axisLabel: { color: t.muted, formatter: (v: number) => compactInt(v) },
      },
      series: [
        { name: 'Call OI', type: 'line', showSymbol: false, connectNulls: true, data: viz.callOi, lineStyle: { color: t.bull, width: 1.75 }, itemStyle: { color: t.bull } },
        { name: 'Put OI', type: 'line', showSymbol: false, connectNulls: true, data: viz.putOi, lineStyle: { color: t.bear, width: 1.75 }, itemStyle: { color: t.bear } },
      ],
    }),
    [viz],
  );
  return (
    <EChart makeOption={makeOption} height={320} ariaLabel="Call versus Put open interest over the session" />
  );
}

interface OiVsPriceChartProps {
  times: string[];
  oi: (number | null)[];
  price: (number | null)[];
  /** 'bull' (Call) or 'bear' (Put) — the OI line tone. */
  oiTone: 'bull' | 'bear';
  legLabel: 'Call' | 'Put';
}

/** Dual-axis: the leg OI (left) + the leg premium price (orange, right). */
export function OiVsPriceChart({ times, oi, price, oiTone, legLabel }: OiVsPriceChartProps) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const oiColor = oiTone === 'bull' ? t.bull : t.bear;
      return {
        ...base(times, t),
        legend: { top: 0, textStyle: { color: t.muted }, data: [`${legLabel} OI`, 'Price'] },
        grid: { left: 56, right: 56, top: 36, bottom: 28 },
        yAxis: [
          {
            type: 'value',
            name: 'OI',
            scale: true,
            position: 'left',
            splitLine: { lineStyle: { color: t.grid } },
            axisLabel: { color: t.muted, formatter: (v: number) => compactInt(v) },
            nameTextStyle: { color: t.muted },
          },
          {
            type: 'value',
            name: 'Price',
            scale: true,
            position: 'right',
            splitLine: { show: false },
            axisLabel: { color: t.muted },
            nameTextStyle: { color: t.muted },
          },
        ],
        series: [
          { name: `${legLabel} OI`, type: 'line', yAxisIndex: 0, showSymbol: false, connectNulls: true, data: oi, lineStyle: { color: oiColor, width: 1.75 }, itemStyle: { color: oiColor } },
          { name: 'Price', type: 'line', yAxisIndex: 1, showSymbol: false, connectNulls: true, data: price, lineStyle: { color: t.warn, width: 1.25 }, itemStyle: { color: t.warn } },
        ],
      };
    },
    [times, oi, price, oiTone, legLabel],
  );
  return (
    <EChart
      makeOption={makeOption}
      height={320}
      ariaLabel={`${legLabel} open interest versus ${legLabel} premium price over the session`}
    />
  );
}

import { useCallback } from 'react';
import type { EChartsOption } from 'echarts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';
import type { ActiveStrikeIvViz, ActiveStrikeSeries } from '../api/activeStrikesFold.ts';

// The two oipulse "Active Strikes OI" charts (§options/active-strikes). Maths pre-folded in
// api/activeStrikesFold; these only shape the ECharts option from the live --ay-* theme tokens.
// Convention: Call OI = bull (green), Put OI = bear (red), Sentiment % = accent (blue).

const compactInt = (n: number) => new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(n);

function lineBase(times: string[], t: ChartTheme): EChartsOption {
  return {
    aria: { enabled: true },
    textStyle: { color: t.text },
    grid: { left: 56, right: 16, top: 36, bottom: 28 },
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

/** Active Strike Change in OI — Call OI (green) + Put OI (red) lines per bucket. */
export function ActiveStrikeOiChart({ data }: { data: ActiveStrikeSeries }) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      ...lineBase(data.times, t),
      legend: { top: 0, textStyle: { color: t.muted }, data: ['Call OI', 'Put OI'] },
      yAxis: {
        type: 'value',
        scale: true,
        splitLine: { lineStyle: { color: t.grid } },
        axisLabel: { color: t.muted, formatter: (v: number) => compactInt(v) },
      },
      series: [
        {
          name: 'Call OI',
          type: 'line',
          showSymbol: false,
          connectNulls: true,
          data: data.callOi,
          lineStyle: { color: t.bull, width: 1.75 },
          itemStyle: { color: t.bull },
        },
        {
          name: 'Put OI',
          type: 'line',
          showSymbol: false,
          connectNulls: true,
          data: data.putOi,
          lineStyle: { color: t.bear, width: 1.75 },
          itemStyle: { color: t.bear },
        },
      ],
    }),
    [data],
  );
  return (
    <EChart
      makeOption={makeOption}
      height={320}
      ariaLabel="Active strike Call versus Put open interest over the session"
    />
  );
}

/** Active Strike Sentiment % — single blue line, unbounded y (deeply negative when calls dominate). */
export function ActiveStrikeSentimentChart({ data }: { data: ActiveStrikeSeries }) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      ...lineBase(data.times, t),
      legend: { top: 0, textStyle: { color: t.muted }, data: ['Sentiment %'] },
      yAxis: {
        type: 'value',
        scale: true,
        splitLine: { lineStyle: { color: t.grid } },
        axisLabel: { color: t.muted, formatter: (v: number) => `${v}%` },
      },
      series: [
        {
          name: 'Sentiment %',
          type: 'line',
          showSymbol: false,
          connectNulls: true,
          data: data.sentiment,
          lineStyle: { color: t.accent, width: 1.75 },
          itemStyle: { color: t.accent },
          markLine: {
            symbol: 'none',
            silent: true,
            lineStyle: { color: t.border, type: 'dashed' },
            data: [{ yAxis: 0 }], // the bull/bear zero line
          },
        },
      ],
    }),
    [data],
  );
  return (
    <EChart
      makeOption={makeOption}
      height={320}
      ariaLabel="Active strike sentiment percent over the session; above zero bullish, below zero bearish"
    />
  );
}

/** Active Strike IV — dual-axis: Call IV (green) + Put IV (red) on the left, Price (orange) on the right. */
export function ActiveStrikeIvChart({ data }: { data: ActiveStrikeIvViz }) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      ...lineBase(data.times, t),
      grid: { left: 56, right: 56, top: 36, bottom: 28 }, // room for the right (price) axis
      legend: { top: 0, textStyle: { color: t.muted }, data: ['Call IV', 'Put IV', 'Price'] },
      yAxis: [
        {
          type: 'value',
          name: 'IV',
          scale: true,
          position: 'left',
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted },
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
        {
          name: 'Call IV',
          type: 'line',
          yAxisIndex: 0,
          showSymbol: false,
          connectNulls: true,
          data: data.callIv,
          lineStyle: { color: t.bull, width: 1.75 },
          itemStyle: { color: t.bull },
        },
        {
          name: 'Put IV',
          type: 'line',
          yAxisIndex: 0,
          showSymbol: false,
          connectNulls: true,
          data: data.putIv,
          lineStyle: { color: t.bear, width: 1.75 },
          itemStyle: { color: t.bear },
        },
        {
          name: 'Price',
          type: 'line',
          yAxisIndex: 1,
          showSymbol: false,
          connectNulls: true,
          data: data.price,
          lineStyle: { color: t.warn, width: 1.25, type: 'dashed' },
          itemStyle: { color: t.warn },
        },
      ],
    }),
    [data],
  );
  return (
    <EChart
      makeOption={makeOption}
      height={360}
      ariaLabel="Active strike Call IV and Put IV versus underlying price over the session"
    />
  );
}

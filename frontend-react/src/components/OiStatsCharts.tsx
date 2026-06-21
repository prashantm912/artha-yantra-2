import { useCallback } from 'react';
import type { EChartsOption } from 'echarts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';
import type { IndividualOi, PcrPricePoint } from '../api/oiStatsFold.ts';

// The three oipulse "OI Statistics" charts (§options/oi-statistics). All maths are pre-folded in
// api/oiStatsFold; these only shape the ECharts option from the live --ay-* theme tokens. Convention:
// Call OI = bull (green), Put OI = bear (red) — the oipulse colour code (Call resistance / Put support).

const compactInt = (n: number) => new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(n);

/** Cumulative OI — two bars: total Call OI (green) vs total Put OI (red). */
export function CumulativeOiChart({
  callOi,
  putOi,
  changeView,
}: {
  callOi: number;
  putOi: number;
  changeView: boolean;
}) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      legend: { top: 0, textStyle: { color: t.muted }, data: ['Call OI', 'Put OI'] },
      grid: { left: 56, right: 16, top: 36, bottom: 28 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
        valueFormatter: (v: unknown) => compactInt(Number(v)),
      },
      xAxis: {
        type: 'category',
        data: [changeView ? 'Δ OI' : 'OI'],
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: t.grid } },
        axisLabel: { color: t.muted, formatter: (v: number) => compactInt(v) },
      },
      series: [
        { name: 'Call OI', type: 'bar', data: [callOi], itemStyle: { color: t.bull }, barMaxWidth: 56 },
        { name: 'Put OI', type: 'bar', data: [putOi], itemStyle: { color: t.bear }, barMaxWidth: 56 },
      ],
    }),
    [callOi, putOi, changeView],
  );
  return (
    <EChart
      makeOption={makeOption}
      height={300}
      ariaLabel={`Cumulative ${changeView ? 'change in ' : ''}open interest: Call ${compactInt(callOi)}, Put ${compactInt(putOi)}`}
    />
  );
}

/** Individual OI — per-strike grouped bars (Call green / Put red) with an ATM marker + dataZoom. */
export function IndividualOiChart({ data, changeView }: { data: IndividualOi; changeView: boolean }) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const atmIdx = data.atm ? data.strikes.indexOf(data.atm) : -1;
      // Centre the initial zoom band on the ATM strike (oipulse opens focused on the at-the-money walls).
      const span = Math.min(24, data.strikes.length);
      const start = atmIdx >= 0 ? Math.max(0, atmIdx - span / 2) : 0;
      const startPct = data.strikes.length ? (start / data.strikes.length) * 100 : 0;
      const endPct = data.strikes.length ? ((start + span) / data.strikes.length) * 100 : 100;
      const markLine =
        atmIdx >= 0
          ? {
              symbol: 'none',
              silent: true,
              label: { formatter: 'ATM', color: t.text, fontSize: 10 },
              lineStyle: { color: t.accent, type: 'dashed' as const },
              data: [{ xAxis: data.atm as string }],
            }
          : undefined;
      return {
        aria: { enabled: true },
        textStyle: { color: t.text },
        legend: { top: 0, textStyle: { color: t.muted }, data: ['Call OI', 'Put OI'] },
        grid: { left: 56, right: 16, top: 36, bottom: 64 },
        tooltip: {
          trigger: 'axis',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          valueFormatter: (v: unknown) => compactInt(Number(v)),
        },
        xAxis: {
          type: 'category',
          data: data.strikes,
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted },
        },
        yAxis: {
          type: 'value',
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted, formatter: (v: number) => compactInt(v) },
        },
        dataZoom: [
          { type: 'inside', start: startPct, end: endPct },
          {
            type: 'slider',
            start: startPct,
            end: endPct,
            height: 20,
            bottom: 24,
            borderColor: t.border,
            textStyle: { color: t.muted },
          },
        ],
        series: [
          {
            name: 'Call OI',
            type: 'bar',
            data: data.ce,
            itemStyle: { color: t.bull },
            markLine,
          },
          { name: 'Put OI', type: 'bar', data: data.pe, itemStyle: { color: t.bear } },
        ],
      };
    },
    [data],
  );
  return (
    <EChart
      makeOption={makeOption}
      height={360}
      ariaLabel={`Per-strike ${changeView ? 'change in ' : ''}open interest, Call versus Put, ATM ${data.atm ?? 'unknown'}`}
    />
  );
}

/** PCR vs price — dual-axis intraday line: PCR (left, accent) and underlying price (right, warn). */
export function PcrPriceChart({ points }: { points: PcrPricePoint[] }) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      legend: { top: 0, textStyle: { color: t.muted }, data: ['PCR', 'Price'] },
      grid: { left: 56, right: 56, top: 36, bottom: 28 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
        axisPointer: { type: 'cross', lineStyle: { color: t.crosshair } },
      },
      xAxis: {
        type: 'category',
        data: points.map((p) => p.time),
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      yAxis: [
        {
          type: 'value',
          name: 'PCR',
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
          name: 'PCR',
          type: 'line',
          yAxisIndex: 0,
          showSymbol: false,
          data: points.map((p) => p.pcr),
          lineStyle: { color: t.accent, width: 1.75 },
          itemStyle: { color: t.accent },
        },
        {
          name: 'Price',
          type: 'line',
          yAxisIndex: 1,
          showSymbol: false,
          data: points.map((p) => p.price),
          lineStyle: { color: t.warn, width: 1.5 },
          itemStyle: { color: t.warn },
        },
      ],
    }),
    [points],
  );
  return (
    <EChart
      makeOption={makeOption}
      height={300}
      ariaLabel="Intraday Put-Call Ratio against underlying price"
    />
  );
}

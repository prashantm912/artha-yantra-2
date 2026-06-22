import { useCallback } from 'react';
import type { EChartsOption } from 'echarts';
import type { VixIndexSeries } from '../core/vixIndexSeries.ts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';

// Vix & Index chart composite (§features/vix-index — oipulse "India Vix Vs. Nifty / Banknifty"). A dual-axis
// LINE chart: INDIA VIX on the LEFT axis (blue/accent, tight range) + the index price on the RIGHT axis
// (orange/warn). Each axis auto-scales independently (`scale:true`) — the spec forbids co-scaling (VIX
// ~13.9–14.8 vs Nifty ~23,820–24,030). Bottom-centered legend, save-PNG + line/bar toolbox, "Oi Pulse"
// watermark. All series maths live in core/vixIndexSeries; this only shapes the option from the live tokens.
// ONE component, rendered twice (Nifty then Banknifty) — `priceLabel` differs.

interface VixIndexChartProps {
  series: VixIndexSeries;
  priceLabel: string;
}

const f2 = (n: number) => n.toFixed(2);
const inIN = (n: number) => n.toLocaleString('en-IN');

export function VixIndexChart({ series, priceLabel }: VixIndexChartProps) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      legend: { bottom: 0, textStyle: { color: t.muted }, data: ['Vix', 'Price'] },
      grid: { left: 56, right: 64, top: 24, bottom: 44 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
        axisPointer: { type: 'cross', lineStyle: { color: t.crosshair } },
        formatter: (raw: unknown) => {
          const arr = raw as Array<{ dataIndex: number }>;
          const i = arr?.[0]?.dataIndex ?? 0;
          const v = series.vix[i];
          const p = series.price[i];
          return [
            `<b>${series.xAxis[i]}</b>`,
            `Vix ${v == null ? '—' : f2(v)}`,
            `${priceLabel} ${p == null ? '—' : inIN(p)}`,
          ].join('<br/>');
        },
      },
      toolbox: {
        right: 12,
        iconStyle: { borderColor: t.muted },
        feature: {
          dataZoom: { yAxisIndex: 'none' },
          magicType: { type: ['line', 'bar'] },
          restore: {},
          saveAsImage: { backgroundColor: t.surface1 },
        },
      },
      xAxis: {
        type: 'category',
        data: series.xAxis,
        boundaryGap: false,
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      // Left = Vix (tight), Right = Price; each auto-scales independently (spec: do NOT co-scale).
      yAxis: [
        {
          type: 'value',
          name: 'Vix',
          scale: true,
          position: 'left',
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted },
          nameTextStyle: { color: t.muted },
        },
        {
          type: 'value',
          name: priceLabel,
          scale: true,
          position: 'right',
          splitLine: { show: false },
          axisLabel: { color: t.muted, formatter: (v: number) => inIN(v) },
          nameTextStyle: { color: t.muted },
        },
      ],
      graphic: [
        {
          type: 'text',
          right: 72,
          bottom: 36,
          style: { text: 'Oi Pulse', fill: t.muted, fontSize: 11, opacity: 0.6 },
        },
      ],
      series: [
        {
          name: 'Vix',
          type: 'line',
          yAxisIndex: 0,
          showSymbol: false,
          connectNulls: true,
          data: series.vix,
          lineStyle: { color: t.accent, width: 1.75 },
          itemStyle: { color: t.accent },
        },
        {
          name: 'Price',
          type: 'line',
          yAxisIndex: 1,
          showSymbol: false,
          connectNulls: true,
          data: series.price,
          lineStyle: { color: t.warn, width: 1.75 },
          itemStyle: { color: t.warn },
        },
      ],
    }),
    [series, priceLabel],
  );

  return (
    <EChart
      makeOption={makeOption}
      height={360}
      ariaLabel={`India VIX versus ${priceLabel} price intraday: VIX on the left axis in blue, price on the right axis in orange`}
    />
  );
}

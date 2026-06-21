import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import type { FutOiCandle } from '../api/types.ts';
import { toFutOiChartSeries } from '../core/futuresOiChartSeries.ts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';

// Futures OI Chart composite (§futures/oi-chart — oipulse "Futures Oi Vs. Price Analysis"). The dual-axis
// combo: the contract's price CANDLESTICK (right axis, green up / red down) + the OI LINE (left axis,
// blue), with day high/low markers, a dataZoom scrubber and the save/zoom toolbox. All series maths live
// in core/futuresOiChartSeries; this only shapes the ECharts option from the live --ay-* theme tokens.

interface FuturesOiChartProps {
  items: FutOiCandle[];
  tradingsymbol: string;
}

const f2 = (n: number) => n.toFixed(2);
const compactInt = (n: number) => new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(n);

export function FuturesOiChart({ items, tradingsymbol }: FuturesOiChartProps) {
  const series = useMemo(() => toFutOiChartSeries(items), [items]);

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const markData =
        series.dayHigh && series.dayLow
          ? [
              {
                name: 'Day High',
                coord: [series.times[series.dayHigh.index], series.dayHigh.value],
                value: f2(series.dayHigh.value),
                symbol: 'triangle',
                symbolSize: 10,
                itemStyle: { color: t.bull },
                label: { color: t.text, fontSize: 10 },
              },
              {
                name: 'Day Low',
                coord: [series.times[series.dayLow.index], series.dayLow.value],
                value: f2(series.dayLow.value),
                symbol: 'triangle',
                symbolRotate: 180,
                symbolSize: 10,
                itemStyle: { color: t.bear },
                label: { color: t.text, fontSize: 10 },
              },
            ]
          : [];

      return {
        aria: { enabled: true },
        textStyle: { color: t.text },
        legend: { top: 0, textStyle: { color: t.muted }, data: ['Oi', 'Price'] },
        grid: { left: 56, right: 56, top: 36, bottom: 64 },
        tooltip: {
          trigger: 'axis',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          axisPointer: { type: 'cross', lineStyle: { color: t.crosshair } },
          formatter: (raw: unknown) => {
            const arr = raw as Array<{ dataIndex: number }>;
            const i = arr?.[0]?.dataIndex ?? 0;
            const c = series.candles[i];
            if (!c) return '';
            const oi = series.oi[i];
            return [
              `<b>${series.times[i]}</b>`,
              `O ${f2(c[0])} · H ${f2(c[3])} · L ${f2(c[2])} · C ${f2(c[1])}`,
              `OI ${oi == null ? '—' : compactInt(oi)}`,
            ].join('<br/>');
          },
        },
        toolbox: {
          right: 12,
          iconStyle: { borderColor: t.muted },
          feature: {
            dataZoom: { yAxisIndex: 'none' },
            restore: {},
            saveAsImage: { backgroundColor: t.surface1 },
          },
        },
        xAxis: {
          type: 'category',
          data: series.times,
          boundaryGap: true,
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted },
        },
        // Left = OI (the line), Right = Price (the candlesticks); each auto-scales independently
        // (oipulse: do NOT co-scale the two axes).
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
        dataZoom: [
          { type: 'inside', start: 0, end: 100 },
          {
            type: 'slider',
            start: 0,
            end: 100,
            height: 20,
            bottom: 28,
            borderColor: t.border,
            textStyle: { color: t.muted },
          },
        ],
        graphic: [
          {
            type: 'text',
            right: 64,
            bottom: 56,
            style: {
              text: `Oi Pulse / ${tradingsymbol}`,
              fill: t.muted,
              fontSize: 11,
              opacity: 0.6,
            },
          },
        ],
        series: [
          {
            name: 'Oi',
            type: 'line',
            yAxisIndex: 0,
            showSymbol: false,
            connectNulls: true,
            data: series.oi,
            lineStyle: { color: t.accent, width: 1.75 },
            itemStyle: { color: t.accent },
          },
          {
            name: 'Price',
            type: 'candlestick',
            yAxisIndex: 1,
            data: series.candles,
            itemStyle: {
              color: t.bull,
              color0: t.bear,
              borderColor: t.bull,
              borderColor0: t.bear,
            },
            markPoint: { symbolSize: 10, data: markData },
          },
        ],
      };
    },
    [series, tradingsymbol],
  );

  return (
    <EChart
      makeOption={makeOption}
      height={440}
      ariaLabel={`Futures OI versus price for ${tradingsymbol}: price candlesticks on the right axis and open interest line on the left`}
    />
  );
}

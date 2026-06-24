import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import type { OptCandle } from '../api/types.ts';
import { toOptChartSeries } from '../core/optionsChartSeries.ts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';

// One leg (Call or Put) of the oipulse Options Chart (§options/options-chart): the option-PREMIUM
// candlestick (right axis) + the OI line (left axis) + a VWAP overlay, with day high/low markers, a
// dataZoom scrubber and the zoom/save toolbox. The premium candle + VWAP are the focus; the OI line is
// the dual-axis overlay. (IV + Volume sub-panes are a deferred follow-up.) Series maths in
// core/optionsChartSeries; this only shapes the ECharts option from the live --ay-* theme tokens.

interface OptionsLegChartProps {
  items: OptCandle[];
  tradingsymbol: string;
  legLabel: 'Call' | 'Put';
}

const f2 = (n: number) => n.toFixed(2);
const compactInt = (n: number) => new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(n);

export function OptionsLegChart({ items, tradingsymbol, legLabel }: OptionsLegChartProps) {
  const series = useMemo(() => toOptChartSeries(items), [items]);

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
        legend: { top: 0, textStyle: { color: t.muted }, data: ['Oi', 'Premium', 'VWAP'] },
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
              `OI ${oi == null ? '—' : compactInt(oi)} · VWAP ${f2(series.vwap[i])}`,
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
        // Left = OI (the line), Right = premium (the candlesticks + VWAP); independent scaling.
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
            name: 'Premium',
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
            style: { text: `Oi Pulse / ${tradingsymbol}`, fill: t.muted, fontSize: 11, opacity: 0.6 },
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
            name: 'Premium',
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
          {
            name: 'VWAP',
            type: 'line',
            yAxisIndex: 1,
            showSymbol: false,
            data: series.vwap,
            lineStyle: { color: t.warn, width: 1.5 },
            itemStyle: { color: t.warn },
          },
        ],
      };
    },
    [series, tradingsymbol],
  );

  return (
    <EChart
      makeOption={makeOption}
      className="h-64 sm:h-80 lg:h-[400px]"
      ariaLabel={`${legLabel} option premium candlestick for ${tradingsymbol} with OI line and VWAP`}
    />
  );
}

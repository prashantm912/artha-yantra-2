import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import type { SpreadCandle } from '../api/types.ts';
import { toSpreadSeries } from '../core/calendarSpreadSeries.ts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';

// Calendar Spread Chart composite (oipulse "Calendar Spread Chart"). The (near − far) premium
// differential as a candlestick plus VWAP (blue), 20 EMA (yellow), and the Near/Far close lines, with
// day high/low markers, a dataZoom scrubber and the save/zoom toolbox. All series maths live in
// core/calendarSpreadSeries; this only shapes the ECharts option from the live theme tokens.

interface CalendarSpreadChartProps {
  items: SpreadCandle[];
  underlying: string;
  strike: string;
  optionType: string;
  nearExpiry: string;
  farExpiry: string;
}

const f2 = (n: number) => n.toFixed(2);

export function CalendarSpreadChart({
  items,
  underlying,
  strike,
  optionType,
  nearExpiry,
  farExpiry,
}: CalendarSpreadChartProps) {
  const series = useMemo(() => toSpreadSeries(items), [items]);

  const watermark = `${underlying} ${strike} ${optionType} · ${nearExpiry} − ${farExpiry}`;

  const last = series.times.length - 1;
  const readout =
    last >= 0
      ? `${series.times[last]} · O ${f2(series.candles[last][0])} · H ${f2(series.candles[last][3])} · L ${f2(series.candles[last][2])} · C ${f2(series.candles[last][1])} · VWAP ${f2(series.vwap[last])} · 20 EMA ${f2(series.ema20[last])}`
      : '';

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const lineBase = { type: 'line', showSymbol: false, smooth: false } as const;
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
        legend: {
          top: 0,
          textStyle: { color: t.muted },
          data: ['Spread', 'VWAP', '20 EMA', 'Near', 'Far'],
        },
        grid: { left: 56, right: 16, top: 36, bottom: 64 },
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
            return [
              `<b>${series.times[i]}</b>`,
              `O ${f2(c[0])} · H ${f2(c[3])} · L ${f2(c[2])} · C ${f2(c[1])}`,
              `Near ${f2(series.near[i])} · Far ${f2(series.far[i])}`,
              `VWAP ${f2(series.vwap[i])} · EMA ${f2(series.ema20[i])}`,
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
        yAxis: {
          type: 'value',
          scale: true,
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted },
        },
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
            right: 24,
            bottom: 56,
            style: { text: watermark, fill: t.muted, fontSize: 11, opacity: 0.6 },
          },
        ],
        series: [
          {
            name: 'Spread',
            type: 'candlestick',
            data: series.candles,
            itemStyle: {
              color: t.bull,
              color0: t.bear,
              borderColor: t.bull,
              borderColor0: t.bear,
            },
            markPoint: { symbolSize: 10, data: markData },
          },
          { ...lineBase, name: 'VWAP', data: series.vwap, lineStyle: { color: t.accent, width: 1.75 }, itemStyle: { color: t.accent } },
          { ...lineBase, name: '20 EMA', data: series.ema20, lineStyle: { color: t.warn, width: 1.5 }, itemStyle: { color: t.warn } },
          {
            ...lineBase,
            name: 'Near',
            data: series.near,
            lineStyle: { color: t.bull, width: 1.25, type: 'dashed' },
            itemStyle: { color: t.bull },
          },
          {
            ...lineBase,
            name: 'Far',
            data: series.far,
            lineStyle: { color: t.bear, width: 1.25, type: 'dashed' },
            itemStyle: { color: t.bear },
          },
        ],
      };
    },
    [series, watermark],
  );

  return (
    <div>
      {readout && (
        <p className="mb-1 text-center text-xs tabular-nums text-ay-muted" aria-live="polite">
          {readout}
        </p>
      )}
      <EChart
        makeOption={makeOption}
        className="h-64 sm:h-80 lg:h-[440px]"
        ariaLabel={`Calendar spread premium candlestick for ${watermark} with VWAP, 20 EMA, Near and Far price lines`}
      />
    </div>
  );
}

import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import type { StraddleCandle } from '../api/types.ts';
import { toStraddleSeries } from '../core/straddleSeries.ts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';

// Straddle Chart composite (§20.7.6 — oipulse "Options Straddle Chart"). The combined CE+PE premium
// candlestick plus the four overlays oipulse draws: VWAP (blue), 20 EMA (yellow), Call Price + Put
// Price lines, with day high/low markers, a dataZoom scrubber and the save/zoom toolbox. All series
// maths live in core/straddleSeries; this only shapes the ECharts option from the live theme tokens.

interface StraddleChartProps {
  items: StraddleCandle[];
  callStrike: string | null;
  putStrike: string | null;
  underlying: string;
}

const f2 = (n: number) => n.toFixed(2);

export function StraddleChart({ items, callStrike, putStrike, underlying }: StraddleChartProps) {
  const series = useMemo(() => toStraddleSeries(items), [items]);

  // oipulse watermark: straddle = "NAME STRIKE"; strangle = "NAME CALL CE x PUT PE".
  const watermark =
    callStrike && putStrike && callStrike === putStrike
      ? `${underlying} ${callStrike}`
      : `${underlying} ${callStrike ?? '?'} CE x ${putStrike ?? '?'} PE`;

  // The fixed latest-candle readout oipulse shows above the chart (its "Last Updated" strip).
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
          data: ['Straddle', 'VWAP', '20 EMA', 'Call Price', 'Put Price'],
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
              `Call ${f2(series.call[i])} · Put ${f2(series.put[i])}`,
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
            name: 'Straddle',
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
            name: 'Call Price',
            data: series.call,
            lineStyle: { color: t.bull, width: 1.25, type: 'dashed' },
            itemStyle: { color: t.bull },
          },
          {
            ...lineBase,
            name: 'Put Price',
            data: series.put,
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
        ariaLabel={`Straddle premium candlestick for ${watermark} with VWAP, 20 EMA, Call and Put price lines`}
      />
    </div>
  );
}

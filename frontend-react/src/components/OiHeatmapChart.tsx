import { useCallback } from 'react';
import type { EChartsOption } from 'echarts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';
import type { OiHeatmap, OiHeatmapCell } from '../api/types.ts';

// The oipulse-style OI-change heatmap (§20 breadth, grid-heatmap archetype). One leg's grid: rows =
// strikes (low→high), cols = time buckets, cell colour = interval ΔOI on a diverging scale centred on
// zero (green = fresh OI build, red = unwind). maxAbs (computed server-side across BOTH legs) anchors
// the symmetric visualMap so CE and PE share one colour scale and read comparably.

const compactInt = (n: number) =>
  new Intl.NumberFormat('en-IN', { notation: 'compact', signDisplay: 'always' }).format(n);

/** ECharts heatmap data is [colIndex, rowIndex, value]; null ΔOI cells are dropped (rendered as gaps). */
function toData(cells: OiHeatmapCell[]): Array<[number, number, number]> {
  const out: Array<[number, number, number]> = [];
  for (const c of cells) {
    if (c.value !== null) out.push([c.x, c.y, c.value]);
  }
  return out;
}

interface LegHeatmapProps {
  data: OiHeatmap;
  cells: OiHeatmapCell[];
  legLabel: string;
  ariaLabel: string;
}

function LegHeatmap({ data, cells, legLabel, ariaLabel }: LegHeatmapProps) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const max = data.maxAbs > 0 ? data.maxAbs : 1;
      return {
        aria: { enabled: true },
        textStyle: { color: t.text },
        grid: { left: 64, right: 16, top: 16, bottom: 56 },
        tooltip: {
          position: 'top',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          formatter: (p: unknown) => {
            const params = p as { value: [number, number, number] };
            const [x, y, v] = params.value;
            return `${data.strikes[y]} ${legLabel} · ${data.buckets[x]}<br/>ΔOI ${compactInt(v)}`;
          },
        },
        xAxis: {
          type: 'category',
          data: data.buckets,
          splitArea: { show: true },
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted, rotate: 60, interval: 'auto' },
        },
        yAxis: {
          type: 'category',
          data: data.strikes,
          splitArea: { show: true },
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted },
        },
        visualMap: {
          min: -max,
          max,
          calculable: true,
          orient: 'horizontal',
          left: 'center',
          bottom: 0,
          inRange: { color: [t.bear, t.surface1, t.bull] }, // unwind (red) → flat → build (green)
          textStyle: { color: t.muted },
        },
        series: [
          {
            name: `${legLabel} ΔOI`,
            type: 'heatmap',
            data: toData(cells),
            emphasis: { itemStyle: { borderColor: t.text, borderWidth: 1 } },
            progressive: 0,
          },
        ],
      };
    },
    [data, cells, legLabel],
  );
  return <EChart makeOption={makeOption} className="h-64 sm:h-80 lg:h-[420px]" ariaLabel={ariaLabel} />;
}

/** Call (CE) OI-change heatmap. */
export function CallOiHeatmap({ data }: { data: OiHeatmap }) {
  return (
    <LegHeatmap
      data={data}
      cells={data.ce}
      legLabel="CE"
      ariaLabel="Call open-interest change by strike and time; green is fresh build-up, red is unwinding"
    />
  );
}

/** Put (PE) OI-change heatmap. */
export function PutOiHeatmap({ data }: { data: OiHeatmap }) {
  return (
    <LegHeatmap
      data={data}
      cells={data.pe}
      legLabel="PE"
      ariaLabel="Put open-interest change by strike and time; green is fresh build-up, red is unwinding"
    />
  );
}

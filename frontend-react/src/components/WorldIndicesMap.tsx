import { useCallback } from 'react';
import * as echarts from 'echarts';
import type { EChartsOption } from 'echarts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';
import { formatDecimal } from '../lib/decimal.ts';
import type { WorldIndex } from '../api/types.ts';
import worldGeo from '../assets/world.geo.json';

// World Indices map (#14 — the oipulse "World Indices" world map, echarts geo replacing oipulse's
// amCharts). A muted base world map carries geography; one pulsing bubble per global index sits at its
// home-city coordinate, coloured by sign (green up / red down) and sized by |% change|. The bubble — not
// a country choropleth — is the datum, so overlapping indices in one country (the four US ones) each get
// a distinct point and a country with no exact point still plots at its centroid. The sortable table
// below keeps the exact numbers; this map is the at-a-glance "who's up, who's down" read.

// The world GeoJSON ships in this (lazy) route chunk only — register it once with echarts' global map
// registry (the EChart atom shares the same echarts singleton, so the 'world' map is visible to it).
echarts.registerMap('world', worldGeo as unknown as Parameters<typeof echarts.registerMap>[1]);

const num = (s: string | null): number => (s == null ? 0 : Number(s));
const f2 = (s: string | null) => (s == null ? '—' : formatDecimal(s, 2));

// Home-city [lon, lat] per index (name → point). The four US indices are spread across the country so
// their bubbles don't stack. Keyed UPPER-CASE to fold the feed's casing.
const INDEX_POINT: Record<string, [number, number]> = {
  'HANG SENG': [114.17, 22.32],
  'DOW JONES': [-74.0, 40.71],
  'S&P': [-90.2, 38.6],
  'US TECH 100': [-119.4, 36.2],
  'US 30': [-98.5, 31.0],
  'FTSE 100': [-0.13, 51.51],
  'GIFT NIFTY': [72.68, 23.16],
  DAX: [8.68, 50.11],
  'CAC 40': [2.35, 48.86],
  'NIKKEI 225': [139.69, 35.69],
};

// Country centroid fallback so a NEW index (no exact point) still lands on the right country.
const COUNTRY_CENTROID: Record<string, [number, number]> = {
  America: [-98, 39],
  UK: [-1.5, 52.5],
  'Hong Kong': [114.17, 22.32],
  India: [79, 22],
  Germany: [10.4, 51.1],
  France: [2.2, 46.5],
  Japan: [138, 36.5],
  China: [104, 35],
  Singapore: [103.8, 1.35],
  'South Korea': [127.8, 36.5],
  Australia: [133, -25],
  Canada: [-106, 56],
  Brazil: [-51, -10],
};

interface MapPoint {
  name: string;
  value: [number, number, number]; // [lon, lat, changePct]
  row: WorldIndex;
}

function pointFor(r: WorldIndex): [number, number] | null {
  return INDEX_POINT[r.name.toUpperCase()] ?? (r.country ? COUNTRY_CENTROID[r.country] ?? null : null);
}

export function WorldIndicesMap({ rows }: { rows: WorldIndex[] }) {
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const points: MapPoint[] = rows
        .filter((r) => r.changePct != null)
        .map((r) => {
          const p = pointFor(r);
          return p ? { name: r.name, value: [p[0], p[1], num(r.changePct)], row: r } : null;
        })
        .filter((p): p is MapPoint => p != null);

      const maxAbs = Math.max(0.5, ...points.map((p) => Math.abs(p.value[2])));

      return {
        backgroundColor: 'transparent',
        tooltip: {
          trigger: 'item',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          formatter: (raw: unknown) => {
            const d = (raw as { data?: MapPoint }).data;
            if (!d) return '';
            const r = d.row;
            const pct = num(r.changePct);
            return [
              `<b>${r.name}</b>${r.country ? ` · ${r.country}` : ''}`,
              `LTP ${f2(r.ltp)}`,
              `Net ${r.netChange != null && num(r.netChange) >= 0 ? '+' : ''}${f2(r.netChange)} · ${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`,
            ].join('<br/>');
          },
        },
        geo: {
          map: 'world',
          roam: false,
          silent: true,
          left: 0,
          right: 0,
          top: 8,
          bottom: 8,
          itemStyle: { areaColor: t.surface1, borderColor: t.border, borderWidth: 0.5 },
          emphasis: { disabled: true },
        },
        series: [
          {
            // Plain scatter (NOT effectScatter): effectScatter's rippleEffect runs a perpetual
            // requestAnimationFrame loop that never settles under jsdom, hanging the RTL spec that
            // mounts this page. Static bubbles carry the same read (colour + size + label).
            type: 'scatter',
            coordinateSystem: 'geo',
            geoIndex: 0,
            zlevel: 1,
            symbolSize: (val: unknown) => {
              const pct = Math.abs((val as number[])[2]);
              return 9 + 15 * Math.min(1, pct / maxAbs);
            },
            label: {
              show: true,
              position: 'right',
              formatter: (raw: unknown) => {
                const d = (raw as { data?: MapPoint }).data;
                if (!d) return '';
                const pct = d.value[2];
                return `${d.name} ${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`;
              },
              color: t.text,
              fontSize: 10,
              fontWeight: 600,
              textBorderColor: t.surface1,
              textBorderWidth: 2,
            },
            itemStyle: {
              color: (raw: unknown) => {
                const pct = (raw as { data?: MapPoint }).data?.value[2] ?? 0;
                return pct >= 0 ? t.bull : t.bear;
              },
            },
            data: points,
          },
        ],
      };
    },
    [rows],
  );

  return (
    <EChart
      makeOption={makeOption}
      className="h-72 sm:h-96 lg:h-[460px]"
      ariaLabel="World indices map — each global index plotted at its home country, coloured by daily change"
    />
  );
}

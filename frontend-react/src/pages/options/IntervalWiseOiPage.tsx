import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { useIntervalWiseOi } from '../../api/oiAnalytics.ts';
import type { IntervalWiseOi, StrikeMove } from '../../api/types.ts';
import { oiIntMeta, type OiSeverity } from '../../core/oiInterpretation.ts';
import { BarChart3 } from 'lucide-react';
import { FilterBar } from '../../components/FilterBar.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// Interval-wise OI (oipulse §options/interval-wise-oi): top OI gainer/loser strikes across three
// lookbacks (15 min, 60 min, daily) as bars coloured by the 4-state OI interpretation. Six charts —
// a gainers row and a losers row. Backend reuses the spurt fold per timeframe.

const severityColor = (sev: OiSeverity, t: ChartTheme): string =>
  sev === 'success' ? t.bull : sev === 'danger' ? t.bear : sev === 'info' ? t.accent : t.warn;

// Compact ΔOI axis labels (Indian grouping: K/L/Cr). The daily lookback is crore-scale, so the raw
// number clips to ",000,000" inside the narrow 3-col grid — abbreviate it instead (same as OI Stats).
const compactOi = new Intl.NumberFormat('en-IN', { notation: 'compact' });

const CHARTS: { title: string; pick: (d: IntervalWiseOi) => StrikeMove[] }[] = [
  { title: 'OI Gainer · 15 min', pick: (d) => d.gainers15 },
  { title: 'OI Gainer · 60 min', pick: (d) => d.gainers60 },
  { title: 'OI Gainer · Daily', pick: (d) => d.gainersDaily },
  { title: 'OI Loser · 15 min', pick: (d) => d.losers15 },
  { title: 'OI Loser · 60 min', pick: (d) => d.losers60 },
  { title: 'OI Loser · Daily', pick: (d) => d.losersDaily },
];

const LEGEND: { label: string; sev: OiSeverity; varName: string }[] = [
  { label: 'Long Buildup', sev: 'success', varName: '--ay-bull' },
  { label: 'Short Buildup', sev: 'danger', varName: '--ay-bear' },
  { label: 'Long Unwinding', sev: 'warn', varName: '--ay-warn' },
  { label: 'Short Covering', sev: 'info', varName: '--ay-accent' },
];

export function IntervalWiseOiPage() {
  const q = useIntervalWiseOi();
  const data = q.data ?? null;

  const series = useMemo(() => CHARTS.map((c) => (data ? c.pick(data) : [])), [data]);

  const makeOption = useCallback(
    (moves: StrikeMove[]) =>
      (t: ChartTheme): EChartsOption => ({
        aria: { enabled: true },
        textStyle: { color: t.text },
        grid: { left: 56, right: 12, top: 12, bottom: 64 },
        tooltip: {
          trigger: 'axis',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          axisPointer: { type: 'shadow' },
        },
        xAxis: {
          type: 'category',
          data: moves.map((m) => m.strike),
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted, rotate: 45, fontSize: 10 },
        },
        yAxis: {
          type: 'value',
          name: 'ΔOI',
          scale: true,
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted, formatter: (v: number) => compactOi.format(v) },
        },
        series: [
          {
            type: 'bar',
            data: moves.map((m) => ({
              value: m.oiChange,
              itemStyle: { color: severityColor(oiIntMeta(m.interpretation).severity, t) },
            })),
          },
        ],
      }),
    [],
  );

  const options = useMemo(() => series.map((moves) => makeOption(moves)), [series, makeOption]);

  return (
    <LoadBeat>
      <PageHeader title="Interval-wise OI" help="Shows the strikes adding the most OI (gainers) and shedding the most OI (losers) over 15-minute, 60-minute and daily windows, colour-coded by what the price-vs-OI move implies." subtitle="Top OI gainer/loser strikes across 15 min, 60 min and daily lookbacks" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry />
        <GoButton onClick={() => void q.refetch()} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => data == null}
        empty={{
          icon: BarChart3,
          title: 'No interval-wise OI — pick an underlying + expiry with captured snapshots.',
        }}
        errorTitle="Couldn't load interval-wise OI"
        skeleton={
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            {CHARTS.map((c) => (
              <Skeleton key={c.title} variant="chart-block" height={240} />
            ))}
          </div>
        }
      >
        {() => (
          <BeatBlock>
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
              {CHARTS.map((c, i) => (
                <div key={c.title} className="card shadow-e1">
                  <h2 className="mb-1 text-h3 text-ay-text">{c.title}</h2>
                  {series[i].length > 0 ? (
                    <EChart makeOption={options[i]} height={240} ariaLabel={`${c.title} top strikes by OI change`} />
                  ) : (
                    <p className="py-8 text-center text-xs text-ay-muted">No data for this window.</p>
                  )}
                </div>
              ))}
            </div>

            <p className="mt-3 flex flex-wrap justify-center gap-x-4 gap-y-1 text-xs text-ay-muted">
              {LEGEND.map((l) => (
                <span key={l.label} className="inline-flex items-center gap-1.5">
                  <span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: `var(${l.varName})` }} />
                  {l.label}
                </span>
              ))}
            </p>
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

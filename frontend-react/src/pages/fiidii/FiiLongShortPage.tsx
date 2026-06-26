import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { useFiiLongShort } from '../../api/oiAnalytics.ts';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// FII Long-Short Ratio (oipulse §fii-dii/fii-long-short-ratio): FII index-futures long% over time. LSR%
// = fiiLong / (fiiLong + fiiShort) × 100 (low = bearish FII, high = bullish). The BE /long-short feed
// gives fiiLong/fiiShort per day. oipulse overlays NIFTY-futures candles on a left price axis — that
// overlay is DEFERRED here (needs a futures-candle instrument feed we don't yet expose; a Wave-4 chart
// concern); this ships the primary LSR% line + the dynamic "FII are long for X%" title.

const isoDaysAgo = (days: number): string => {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
};

const lsrPct = (long: number, short: number): number | null => {
  const denom = long + short;
  return denom === 0 ? null : (long / denom) * 100;
};

export function FiiLongShortPage() {
  // Full-history page: a wide default window (our capture is forward-only, so points accrue from boot).
  const to = useMemo(() => isoDaysAgo(0), []);
  const from = useMemo(() => isoDaysAgo(540), []);
  const q = useFiiLongShort(from, to);

  const rows = useMemo(
    () => [...(q.data ?? [])].sort((a, b) => a.tradeDate.localeCompare(b.tradeDate)),
    [q.data],
  );

  const lastLsr = rows.length ? lsrPct(rows[rows.length - 1].fiiLong, rows[rows.length - 1].fiiShort) : null;
  const lastDate = rows.length ? rows[rows.length - 1].tradeDate : null;

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      legend: { top: 0, textStyle: { color: t.muted }, data: ['FII IDX Long Short Ratio'] },
      grid: { left: 48, right: 16, top: 36, bottom: 64 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
        axisPointer: { type: 'cross', lineStyle: { color: t.crosshair } },
      },
      xAxis: {
        type: 'category',
        data: rows.map((r) => r.tradeDate),
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      yAxis: {
        type: 'value',
        name: 'L.S.R %',
        scale: true,
        splitLine: { lineStyle: { color: t.grid } },
        axisLabel: { color: t.muted, formatter: '{value}%' },
      },
      dataZoom: [
        { type: 'inside', start: 0, end: 100 },
        { type: 'slider', start: 0, end: 100, height: 20, bottom: 28, borderColor: t.border, textStyle: { color: t.muted } },
      ],
      graphic: [
        { type: 'text', right: 24, bottom: 56, style: { text: 'OiPulse', fill: t.muted, fontSize: 11, opacity: 0.5 } },
      ],
      series: [
        {
          name: 'FII IDX Long Short Ratio',
          type: 'line',
          showSymbol: false,
          data: rows.map((r) => {
            const v = lsrPct(r.fiiLong, r.fiiShort);
            return v == null ? null : Number(v.toFixed(2));
          }),
          lineStyle: { color: t.bear, width: 1.75 },
          itemStyle: { color: t.bear },
        },
      ],
    }),
    [rows],
  );

  return (
    <LoadBeat>
      <PageHeader
        title={
          lastLsr != null && lastDate
            ? `FII Long Short Ratio — As on ${lastDate}, FII are long for ${lastLsr.toFixed(2)}%`
            : 'FII Long Short Ratio'
        }
        subtitle="LSR % = FII index-futures long ÷ (long + short). Low = FII bearish, high = bullish. Nifty-price overlay deferred (Wave 4)."
        help="Plots the share of FII index-futures positions that are long over time — a rising line means foreign investors are leaning more bullish, a falling line more bearish."
      />

      <QueryState
        query={q}
        isEmpty={() => rows.length === 0}
        empty={{ title: 'No FII participant-OI history captured for this window yet.' }}
        errorTitle="Couldn't load FII long-short ratio"
        skeleton={<Skeleton variant="chart-block" className="h-64 sm:h-80 lg:h-[440px]" />}
      >
        {() => (
          <BeatBlock className="card shadow-e1">
            <EChart
              makeOption={makeOption}
              className="h-64 sm:h-80 lg:h-[440px]"
              ariaLabel="FII index-futures long-short ratio percentage over time"
            />
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

import { useCallback, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { Activity } from 'lucide-react';
import { useChainTable } from '../../api/oiAnalytics.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { formatDecimal } from '../../lib/decimal.ts';
import type { ChainTableRow } from '../../api/types.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// IV Smile (audit §6.9 / §2.4-3): strike-vs-implied-volatility across one expiry, off the live /chain-table
// IV column (a single Black-76 IV per strike — the CE and PE share the value, so the smile is one curve).
// The classic volatility skew: wings richer than the ATM. Derived-history sessions carry null IV, so on
// those the chart honestly shows an empty note rather than a fabricated flat line (house data-fidelity
// doctrine). IV rides the wire as a fraction (0.1396) → ×100 for the percent axis; the ×100 crosses to a
// number only as a chart coordinate. Lazy-loaded (bears the ECharts bundle).

const WINDOWS = ['10', '15', '25', 'All'] as const;
type Window = (typeof WINDOWS)[number];

const n = (s: string | null): number => (s == null ? 0 : Number(s));

/** N strikes nearest the ATM (by |strike − atm|), then re-sorted ascending for the axis. */
function windowed(rows: ChainTableRow[], atm: string | null, win: Window): ChainTableRow[] {
  if (win === 'All' || atm == null) return [...rows].sort((a, b) => n(a.strike) - n(b.strike));
  const count = Number(win);
  const atmN = n(atm);
  return [...rows]
    .sort((a, b) => Math.abs(n(a.strike) - atmN) - Math.abs(n(b.strike) - atmN))
    .slice(0, count)
    .sort((a, b) => n(a.strike) - n(b.strike));
}

/** The single per-strike IV (CE first, PE fallback), as a percent number, or null when untraded. */
function ivPctOf(row: ChainTableRow): number | null {
  const raw = row.ce?.leg.iv ?? row.pe?.leg.iv ?? null;
  return raw == null ? null : Number(raw) * 100;
}

export function IvSmilePage() {
  const chainQ = useChainTable();
  const [win, setWin] = useState<Window>('25');

  const chain = chainQ.data ?? null;
  const spot = chain?.spot ?? null;
  const allRows = useMemo(() => chain?.rows ?? [], [chain]);
  const atm = useMemo(() => nearestStrike(allRows.map((r) => r.strike), spot), [allRows, spot]);
  const rows = useMemo(() => windowed(allRows, atm, win), [allRows, atm, win]);
  const hasIv = useMemo(() => rows.some((r) => ivPctOf(r) != null), [rows]);

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      legend: { top: 0, textStyle: { color: t.muted }, data: ['IV'] },
      grid: { left: 52, right: 16, top: 36, bottom: 48 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
        valueFormatter: (v: unknown) => (v == null ? '—' : `${Number(v).toFixed(2)}%`),
      },
      xAxis: {
        type: 'category',
        name: 'Strike',
        nameLocation: 'middle',
        nameGap: 30,
        nameTextStyle: { color: t.muted },
        data: rows.map((r) => r.strike),
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      yAxis: {
        type: 'value',
        name: 'IV %',
        scale: true,
        splitLine: { lineStyle: { color: t.grid } },
        axisLabel: { color: t.muted, formatter: '{value}%' },
      },
      graphic: [
        {
          type: 'text',
          right: 24,
          bottom: 36,
          style: { text: 'ArthaYantra', fill: t.muted, fontSize: 11, opacity: 0.5 },
        },
      ],
      series: [
        {
          name: 'IV',
          type: 'line',
          smooth: true,
          connectNulls: false,
          showSymbol: false,
          data: rows.map((r) => ivPctOf(r)),
          lineStyle: { color: t.accent, width: 2 },
          itemStyle: { color: t.accent },
          markLine: atm
            ? {
                symbol: 'none',
                data: [{ xAxis: atm }],
                lineStyle: { color: t.muted, type: 'dashed' },
                label: { formatter: 'ATM', color: t.muted },
              }
            : undefined,
        },
      ],
    }),
    [rows, atm],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="IV Smile"
        help="Plots each strike's implied volatility across one expiry — the classic 'smile' where out-of-the-money wings are usually pricier than the at-the-money strike. A steep skew means the market is paying up for downside (or upside) protection."
        subtitle="Strike-vs-IV skew from the live chain — one Black-76 IV per strike"
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval={false} />
        <Select
          ariaLabel="Show strikes"
          title="How many strikes around at-the-money to plot"
          value={win}
          options={[...WINDOWS]}
          onChange={(v) => setWin(v as Window)}
        />
        <GoButton onClick={() => chainQ.refetch()} loading={chainQ.isFetching} />
      </div>

      <BeatStrip className="card shadow-e1 mb-3 flex flex-wrap items-center gap-2 text-sm text-ay-muted">
        {spot && (
          <BeatItem>
            <Metric label="Spot" value={formatDecimal(spot, 2)} />
          </BeatItem>
        )}
        {atm && (
          <BeatItem>
            <Metric label="ATM" value={atm} />
          </BeatItem>
        )}
        <span className="text-xs">· CE and PE share one per-strike Black-76 IV · IV = fraction ×100</span>
      </BeatStrip>

      <QueryState
        query={chainQ}
        isEmpty={() => allRows.length === 0}
        empty={{
          icon: Activity,
          title: 'No chain — pick an underlying + expiry with a live option chain.',
        }}
        errorTitle="Couldn't load the IV smile"
        skeleton={<Skeleton variant="chart-block" className="h-64 sm:h-80 lg:h-[440px]" />}
      >
        {() => (
          <BeatBlock className="card shadow-e1">
            <h2 className="mb-2 text-h3 text-ay-text">Implied volatility by strike</h2>
            {hasIv ? (
              <EChart
                makeOption={makeOption}
                className="h-64 sm:h-80 lg:h-[440px]"
                ariaLabel="Implied volatility smile — IV percent by strike"
              />
            ) : (
              <p className="py-16 text-center text-sm text-ay-muted">
                No IV in this chain — derived-history sessions carry null IV. The smile needs a
                live-captured (or ATM-band recomputed) session.
              </p>
            )}
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

import { useCallback, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { useBreadth, useBreadthHistory } from '../../api/oiAnalytics.ts';
import type { BreadthDeliveryRow, BreadthDay } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { FreshnessBadge } from '../../components/atoms/FreshnessBadge.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { formatDecimal } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';

// One elevated breadth tile: uppercase wide-tracked caption label / mono value. Counts/percentages
// (not signed flows), so no sign-tone logic — just the figure.
function BreadthStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="card shadow-e1">
      <div className="text-caption uppercase tracking-wide text-ay-muted">{label}</div>
      <div className="mt-1 text-h3 nums font-semibold text-ay-text">{value}</div>
    </div>
  );
}

// ETFs/BeES settle ~100% delivered by construction, so they monopolized the Delivery-% Leaders
// board and made it decorative (audit 2026-07-02 §9.6) — filter them from the leaderboard.
const ETF_NAME = /BEES|ETF|IETF|ILIQ|LIQUID|GOLDSHARE|ADD$/i;

// Equity → Breadth (oipulse): advance/decline + average delivery% + the delivery-% leaders for one
// trade date, read from the NSE cash (EQ+BE) EOD bhavcopy (BreadthController). EOD only — the date defaults
// to the last completed weekday session; the owner picks any past date to value-verify. 422 (no bhavcopy
// for that date) renders the empty state.

/** Most recent weekday strictly before "today" in IST — today's bhavcopy is not published intraday. */
function lastSessionIso(): string {
  const istNow = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Kolkata' }));
  istNow.setDate(istNow.getDate() - 1);
  while (istNow.getDay() === 0 || istNow.getDay() === 6) istNow.setDate(istNow.getDate() - 1);
  const y = istNow.getFullYear();
  const m = String(istNow.getMonth() + 1).padStart(2, '0');
  const d = String(istNow.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

const pct = (s: string | null) => (s ? `${formatDecimal(s, 2)}%` : '—');

export function BreadthPage() {
  const [date, setDate] = useState<string>(lastSessionIso());
  const q = useBreadth(date);
  const data = q.data ?? null;
  const summary = data?.summary ?? null;

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      grid: { left: 84, right: 24, top: 8, bottom: 24 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
        axisPointer: { type: 'shadow' },
      },
      xAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: t.grid } },
        axisLabel: { color: t.muted },
      },
      yAxis: {
        type: 'category',
        data: ['Advances', 'Declines', 'Unchanged'],
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      series: [
        {
          type: 'bar',
          data: [
            { value: summary?.advances ?? 0, itemStyle: { color: t.bull } },
            { value: summary?.declines ?? 0, itemStyle: { color: t.bear } },
            { value: summary?.unchanged ?? 0, itemStyle: { color: t.muted } },
          ],
          label: { show: true, position: 'right', color: t.text },
        },
      ],
    }),
    [summary],
  );

  const columns: DataColumn<BreadthDeliveryRow>[] = useMemo(
    () => [
      { id: 'symbol', header: 'Symbol', help: 'The NSE stock ticker for this delivery-% leader.', align: 'left', render: (r) => r.symbol, mobileLabel: 'Symbol' },
      {
        id: 'delivery',
        header: 'Delivery %',
        help: FIELD_HELP.deliveryPct,
        render: (r) => pct(r.deliveryPct),
        sortValue: (r) => Number(r.deliveryPct ?? 0),
        mobileLabel: 'Delivery %',
      },
      {
        id: 'close',
        header: 'Close',
        help: FIELD_HELP.close,
        render: (r) => (r.close ? formatDecimal(r.close, 2) : '—'),
        sortValue: (r) => Number(r.close ?? 0),
        mobileLabel: 'Close',
      },
      {
        id: 'chg',
        header: '% Chg',
        help: FIELD_HELP.changePct,
        render: (r) => <ValueDeltaCell value={r.pctChange} suffix="%" />,
        sortValue: (r) => Number(r.pctChange ?? 0),
        mobileLabel: '% Chg',
      },
    ],
    [],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="Market Breadth"
        help="Counts how many stocks rose vs fell on a session and lists the delivery-% leaders — more advances than declines signals a broadly strong day."
        subtitle={
          <>
            NSE cash (EQ+BE) EOD bhavcopy · advance/decline + delivery-% leaders
            {data ? ` · ${summary?.tradeDate}` : ''}
          </>
        }
        right={<FreshnessBadge freshness={data?.freshness} />}
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <DateInput value={date} onChange={(v) => setDate(v ?? date)} ariaLabel="Trade date" title="Pick the trade date whose EOD bhavcopy breadth you want to view" />
        <GoButton onClick={() => void q.refetch()} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={() => !summary}
        empty={{ title: `No bhavcopy for ${date}. Pick another trade date.` }}
        errorTitle="Couldn't load market breadth"
        skeleton={
          <div className="space-y-4">
            <Skeleton variant="metric-strip" cols={5} />
            <Skeleton variant="chart-block" height={180} />
            <Skeleton variant="table-rows" rows={6} cols={4} />
          </div>
        }
      >
        {() =>
          summary ? (
            <>
              <BeatStrip className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
                <BeatItem>
                  <BreadthStat label="Advances" value={String(summary.advances)} />
                </BeatItem>
                <BeatItem>
                  <BreadthStat label="Declines" value={String(summary.declines)} />
                </BeatItem>
                <BeatItem>
                  <BreadthStat label="Unchanged" value={String(summary.unchanged)} />
                </BeatItem>
                <BeatItem>
                  <BreadthStat label="Total" value={String(summary.total)} />
                </BeatItem>
                <BeatItem>
                  <BreadthStat label="Avg Delivery" value={pct(summary.avgDeliveryPct)} />
                </BeatItem>
              </BeatStrip>

              <BeatBlock className="card shadow-e1 mb-4">
                <EChart makeOption={makeOption} height={180} ariaLabel="Advances, declines and unchanged counts" />
              </BeatBlock>

              <BeatBlock>
                <h2 className="mb-1 text-h3 text-ay-text">Delivery-% Leaders</h2>
                <DataTable
                  columns={columns}
                  rows={(data?.topDelivery ?? []).filter((r) => !ETF_NAME.test(r.symbol))}
                  rowKey={(r) => r.symbol}
                  pageSize={25}
                  ariaLabel="Delivery percentage leaders"
                  emptyMessage="No delivery data for this date."
                />
              </BeatBlock>
            </>
          ) : null
        }
      </QueryState>

      <BeatBlock className="mt-6">
        <h2 className="mb-1 text-h3 text-ay-text">Breadth history</h2>
        <p className="mb-2 text-caption text-ay-muted">
          Advance/decline + % of stocks above their 50-day MA, materialized nightly from the EQ+BE
          cash bhavcopy. The series accrues forward from deploy.
        </p>
        <BreadthHistoryChart />
      </BeatBlock>
    </LoadBeat>
  );
}

/** The materialized A/D + above-50-DMA% history series (audit §3.3 / §6.10). */
function BreadthHistoryChart() {
  const q = useBreadthHistory(180);
  const items = useMemo(() => q.data?.items ?? [], [q.data]);

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const dates = items.map((d) => d.tradeDate);
      const above50 = items.map((d: BreadthDay) =>
        d.sma50Universe && d.sma50Universe > 0 && d.aboveSma50 != null
          ? Math.round((d.aboveSma50 / d.sma50Universe) * 1000) / 10
          : null,
      );
      return {
        aria: { enabled: true },
        textStyle: { color: t.text },
        grid: { left: 48, right: 48, top: 12, bottom: 48 },
        legend: { bottom: 0, textStyle: { color: t.muted } },
        tooltip: {
          trigger: 'axis',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
        },
        xAxis: {
          type: 'category',
          data: dates,
          boundaryGap: false,
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted },
        },
        yAxis: [
          {
            type: 'value',
            name: 'Count',
            splitLine: { lineStyle: { color: t.grid } },
            axisLabel: { color: t.muted },
          },
          {
            type: 'value',
            name: '% > 50-DMA',
            min: 0,
            max: 100,
            splitLine: { show: false },
            axisLabel: { color: t.muted, formatter: '{value}%' },
          },
        ],
        series: [
          { name: 'Advances', type: 'line', showSymbol: false, data: items.map((d) => d.advances), lineStyle: { color: t.bull }, itemStyle: { color: t.bull } },
          { name: 'Declines', type: 'line', showSymbol: false, data: items.map((d) => d.declines), lineStyle: { color: t.bear }, itemStyle: { color: t.bear } },
          { name: '% > 50-DMA', type: 'line', yAxisIndex: 1, showSymbol: false, data: above50, lineStyle: { color: t.accent }, itemStyle: { color: t.accent } },
        ],
      };
    },
    [items],
  );

  return (
    <QueryState
      query={q}
      isEmpty={() => items.length === 0}
      empty={{ title: 'No breadth history yet — the nightly materialization accrues forward from deploy.' }}
      errorTitle="Couldn't load breadth history"
      skeleton={<Skeleton variant="chart-block" height={260} />}
    >
      {() => (
        <div className="card shadow-e1">
          <EChart makeOption={makeOption} height={260} ariaLabel="Breadth history: advances, declines and percent above 50-day MA" />
        </div>
      )}
    </QueryState>
  );
}

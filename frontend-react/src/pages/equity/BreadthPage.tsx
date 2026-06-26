import { useCallback, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { useBreadth } from '../../api/oiAnalytics.ts';
import type { BreadthDeliveryRow } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
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

// Equity → Breadth (oipulse): advance/decline + average delivery% + the delivery-% leaders for one
// trade date, read from the NSE EQ-series EOD bhavcopy (BreadthController). EOD only — the date defaults
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
            NSE EQ-series EOD bhavcopy · advance/decline + delivery-% leaders
            {data ? ` · ${summary?.tradeDate}` : ''}
          </>
        }
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
                  rows={data?.topDelivery ?? []}
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
    </LoadBeat>
  );
}

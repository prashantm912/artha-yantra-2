import { useCallback, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { useBreadth } from '../../api/oiAnalytics.ts';
import type { BreadthDeliveryRow } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

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
      { id: 'symbol', header: 'Symbol', align: 'left', render: (r) => r.symbol, mobileLabel: 'Symbol' },
      {
        id: 'delivery',
        header: 'Delivery %',
        render: (r) => pct(r.deliveryPct),
        sortValue: (r) => Number(r.deliveryPct ?? 0),
        mobileLabel: 'Delivery %',
      },
      {
        id: 'close',
        header: 'Close',
        render: (r) => (r.close ? formatDecimal(r.close, 2) : '—'),
        sortValue: (r) => Number(r.close ?? 0),
        mobileLabel: 'Close',
      },
      {
        id: 'chg',
        header: '% Chg',
        render: (r) => <ValueDeltaCell value={r.pctChange} suffix="%" />,
        sortValue: (r) => Number(r.pctChange ?? 0),
        mobileLabel: '% Chg',
      },
    ],
    [],
  );

  return (
    <div>
      <h1 className="mb-2 text-base font-semibold text-ay-text">Market Breadth</h1>
      <p className="mb-3 text-xs text-ay-muted">
        NSE EQ-series EOD bhavcopy · advance/decline + delivery-% leaders
        {data ? ` · ${summary?.tradeDate}` : ''}
      </p>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <DateInput value={date} onChange={(v) => setDate(v ?? date)} ariaLabel="Trade date" />
        <GoButton onClick={() => void q.refetch()} loading={q.isFetching} />
      </div>

      {summary ? (
        <>
          <div className="mb-4 flex flex-wrap gap-2">
            <Metric label="Advances" value={String(summary.advances)} />
            <Metric label="Declines" value={String(summary.declines)} />
            <Metric label="Unchanged" value={String(summary.unchanged)} />
            <Metric label="Total" value={String(summary.total)} />
            <Metric label="Avg Delivery" value={pct(summary.avgDeliveryPct)} />
          </div>

          <div className="mb-4">
            <EChart makeOption={makeOption} height={180} ariaLabel="Advances, declines and unchanged counts" />
          </div>

          <h2 className="mb-1 text-sm font-semibold text-ay-text">Delivery-% Leaders</h2>
          <DataTable
            columns={columns}
            rows={data?.topDelivery ?? []}
            rowKey={(r) => r.symbol}
            pageSize={25}
            ariaLabel="Delivery percentage leaders"
            emptyMessage="No delivery data for this date."
          />
        </>
      ) : (
        <p className="py-8 text-center text-sm text-ay-muted">
          No bhavcopy for {date}. Pick another trade date.
        </p>
      )}
    </div>
  );
}

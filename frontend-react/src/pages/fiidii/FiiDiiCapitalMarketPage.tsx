import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { useFiiDiiCash } from '../../api/oiAnalytics.ts';
import { foldFiiDiiCash, type FiiDiiCashRow } from '../../api/fiiDiiCashFold.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// FII/DII Capital Market (oipulse §fii-dii/capital-market): FII + DII cash buy/sell/net flows (₹ Crore).
// Two net-value bar series (FII Net, DII Net — green +, red −) + the detailed flows table with the
// computed In-Market net (= FII Net + DII Net). Daily; newest first in the table, oldest→newest in the
// chart. Bar plotting is numeric (echarts); the table keeps exact-decimal display.

const isoDaysAgo = (days: number): string => {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
};

const n = (s: string | null): number => (s == null ? 0 : Number(s));
const cr = (s: string | null) => (s ? formatDecimal(s, 2) : '—');

export function FiiDiiCapitalMarketPage() {
  const to = useMemo(() => isoDaysAgo(0), []);
  const from = useMemo(() => isoDaysAgo(420), []);
  const q = useFiiDiiCash(from, to);

  const rows = useMemo(() => foldFiiDiiCash(q.data ?? []), [q.data]);
  const asc = useMemo(() => [...rows].reverse(), [rows]); // chart reads oldest→newest

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      // Per-bar colour by sign — encoded on each data item (avoids the echarts callback-param typing).
      const bars = (pick: (r: FiiDiiCashRow) => string | null) =>
        asc.map((r) => {
          const v = n(pick(r));
          return { value: v, itemStyle: { color: v >= 0 ? t.bull : t.bear } };
        });
      return {
        aria: { enabled: true },
        textStyle: { color: t.text },
        legend: { top: 0, textStyle: { color: t.muted }, data: ['FII Net', 'DII Net'] },
        grid: { left: 64, right: 16, top: 36, bottom: 56 },
        tooltip: {
          trigger: 'axis',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          axisPointer: { type: 'shadow' },
        },
        xAxis: {
          type: 'category',
          data: asc.map((r) => r.tradeDate),
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted },
        },
        yAxis: {
          type: 'value',
          name: '₹ Cr',
          scale: true,
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted },
        },
        dataZoom: [
          { type: 'inside', start: 0, end: 100 },
          { type: 'slider', start: 0, end: 100, height: 18, bottom: 24, borderColor: t.border, textStyle: { color: t.muted } },
        ],
        series: [
          { name: 'FII Net', type: 'bar', data: bars((r) => r.fiiNet) },
          { name: 'DII Net', type: 'bar', data: bars((r) => r.diiNet) },
        ],
      };
    },
    [asc],
  );

  // oipulse: "No sort on any column" — columns omit sortValue so the table is read-only.
  const columns: DataColumn<FiiDiiCashRow>[] = [
    { id: 'date', header: 'Date', align: 'left', render: (r) => r.tradeDate, mobileLabel: 'Date' },
    { id: 'fiiBuy', header: 'FII Buy', render: (r) => cr(r.fiiBuy), mobileLabel: 'FII Buy' },
    { id: 'fiiSell', header: 'FII Sell', render: (r) => cr(r.fiiSell), mobileLabel: 'FII Sell' },
    { id: 'fiiNet', header: 'FII Net', render: (r) => <ValueDeltaCell value={r.fiiNet} />, mobileLabel: 'FII Net' },
    { id: 'inMarket', header: 'In Market', render: (r) => <ValueDeltaCell value={r.inMarket} />, mobileLabel: 'In Market' },
    { id: 'diiNet', header: 'DII Net', render: (r) => <ValueDeltaCell value={r.diiNet} />, mobileLabel: 'DII Net' },
    { id: 'diiBuy', header: 'DII Buy', render: (r) => cr(r.diiBuy), mobileLabel: 'DII Buy' },
    { id: 'diiSell', header: 'DII Sell', render: (r) => cr(r.diiSell), mobileLabel: 'DII Sell' },
  ];

  return (
    <div>
      <h1 className="mb-2 text-base font-semibold text-ay-text">FII/DII Capital Market Activity</h1>
      <p className="mb-3 text-xs text-ay-muted">Values in ₹ Crore · In Market = FII Net + DII Net · green = net buy, red = net sell</p>

      {asc.length > 0 && (
        <div className="mb-4">
          <EChart makeOption={makeOption} height={300} ariaLabel="FII Net and DII Net cash flow bars per day" />
        </div>
      )}

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(r) => r.tradeDate}
        pageSize={25}
        ariaLabel="Detailed FII/DII capital market activity"
        emptyMessage="No FII/DII cash flow data for this window."
      />
    </div>
  );
}

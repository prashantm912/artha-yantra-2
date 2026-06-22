import { useMemo, useState } from 'react';
import { useEquityDelivery } from '../../api/oiAnalytics.ts';
import type { DeliveryDay } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Equity → Delivery Data (oipulse): one stock's daily delivery quantity / % over a relative period (the
// most recent N trading sessions) from the NSE EQ bhavcopy. % delivery is the institutional-conviction
// read. No date picker — only a period. Announcement count is deferred (no feed). Symbol is a plain text
// input for v1 (the F&O-symbol autocomplete is a later polish).

const PERIODS = [
  { value: '7', label: 'Last 7 Days' },
  { value: '15', label: 'Last 15 Days' },
  { value: '30', label: 'Last 30 Days' },
  { value: '60', label: 'Last 2 Months' },
  { value: '90', label: 'Last 3 Months' },
  { value: '180', label: 'Last 6 Months' },
];

const dec = (s: string | null) => (s == null ? '—' : formatDecimal(s, 2));
const qty = (n: number | null) => (n == null ? '—' : new Intl.NumberFormat('en-IN').format(n));

export function DeliveryDataPage() {
  const [input, setInput] = useState('');
  const [symbol, setSymbol] = useState<string | null>(null);
  const [days, setDays] = useState(15);
  const q = useEquityDelivery(symbol, days);
  const data = q.data ?? null;

  const submit = () => {
    const s = input.trim().toUpperCase();
    if (s) setSymbol(s);
  };

  const columns: DataColumn<DeliveryDay>[] = useMemo(
    () => [
      { id: 'date', header: 'Date', align: 'left', render: (r) => r.date, mobileLabel: 'Date' },
      { id: 'open', header: 'Open', render: (r) => dec(r.open), mobileLabel: 'Open' },
      { id: 'high', header: 'High', render: (r) => dec(r.high), mobileLabel: 'High' },
      { id: 'low', header: 'Low', render: (r) => dec(r.low), mobileLabel: 'Low' },
      { id: 'close', header: 'Close', render: (r) => dec(r.close), mobileLabel: 'Close' },
      {
        id: 'ltpChg',
        header: '% LTP Chg',
        render: (r) => <ValueDeltaCell value={r.ltpChangePct} suffix="%" />,
        sortValue: (r) => Number(r.ltpChangePct ?? 0),
        mobileLabel: '% LTP Chg',
      },
      {
        id: 'deliv',
        header: '% Delivery',
        render: (r) => (r.deliveryPct == null ? '—' : `${formatDecimal(r.deliveryPct, 2)}%`),
        sortValue: (r) => Number(r.deliveryPct ?? 0),
        mobileLabel: '% Delivery',
      },
      {
        id: 'range',
        header: 'Day Range',
        render: (r) =>
          r.dayRange == null ? '—' : `${dec(r.dayRange)} (${dec(r.dayRangePct)}%)`,
        mobileLabel: 'Day Range',
      },
      { id: 'dq', header: 'Delivery Qty', render: (r) => qty(r.deliveryQty), mobileLabel: 'Delivery Qty' },
      { id: 'ttq', header: 'Total Traded Qty', render: (r) => qty(r.totalTradedQty), mobileLabel: 'Total Traded Qty' },
    ],
    [],
  );

  return (
    <div>
      <h1 className="mb-2 text-base font-semibold text-ay-text">Delivery Data</h1>
      <p className="mb-3 text-xs text-ay-muted">
        Daily delivery % &amp; quantity for a stock (high % delivery = institutional conviction)
      </p>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          placeholder="Symbol (e.g. AXISBANK)"
          aria-label="Stock symbol"
          className="h-9 w-48 rounded-md border border-ay-border bg-surface-1 px-2 text-sm uppercase text-ay-text outline-none focus:border-accent"
        />
        <Select
          value={String(days)}
          options={PERIODS}
          onChange={(v) => setDays(Number(v))}
          ariaLabel="Period"
        />
        <GoButton onClick={submit} loading={q.isFetching} />
        {data && <span className="text-xs text-ay-muted">{data.symbol}</span>}
      </div>

      {symbol == null ? (
        <p className="py-8 text-center text-sm text-ay-muted">Enter a stock symbol and press Go.</p>
      ) : (
        <DataTable
          columns={columns}
          rows={data?.items ?? []}
          rowKey={(r) => r.date}
          pageSize={30}
          ariaLabel={`${symbol} daily delivery data`}
          emptyMessage={`No EQ bhavcopy for ${symbol}.`}
        />
      )}
    </div>
  );
}

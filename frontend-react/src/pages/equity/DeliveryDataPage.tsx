import { useMemo, useState } from 'react';
import { Search } from 'lucide-react';
import { useEquityDelivery } from '../../api/oiAnalytics.ts';
import type { DeliveryDay } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { EmptyCard } from '../../components/EmptyCard.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { formatDecimal } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';

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
      { id: 'date', header: 'Date', help: 'The trading session this row of delivery data is for.', align: 'left', render: (r) => r.date, mobileLabel: 'Date' },
      { id: 'open', header: 'Open', help: FIELD_HELP.open, render: (r) => dec(r.open), mobileLabel: 'Open' },
      { id: 'high', header: 'High', help: FIELD_HELP.high, render: (r) => dec(r.high), mobileLabel: 'High' },
      { id: 'low', header: 'Low', help: FIELD_HELP.low, render: (r) => dec(r.low), mobileLabel: 'Low' },
      { id: 'close', header: 'Close', help: FIELD_HELP.close, render: (r) => dec(r.close), mobileLabel: 'Close' },
      {
        id: 'ltpChg',
        header: '% LTP Chg',
        help: FIELD_HELP.changePct,
        render: (r) => <ValueDeltaCell value={r.ltpChangePct} suffix="%" />,
        sortValue: (r) => Number(r.ltpChangePct ?? 0),
        mobileLabel: '% LTP Chg',
      },
      {
        id: 'deliv',
        header: '% Delivery',
        help: FIELD_HELP.deliveryPct,
        render: (r) => (r.deliveryPct == null ? '—' : `${formatDecimal(r.deliveryPct, 2)}%`),
        sortValue: (r) => Number(r.deliveryPct ?? 0),
        mobileLabel: '% Delivery',
      },
      {
        id: 'range',
        header: 'Day Range',
        help: "The session's high-minus-low spread (and that range as a percentage of price).",
        render: (r) =>
          r.dayRange == null ? '—' : `${dec(r.dayRange)} (${dec(r.dayRangePct)}%)`,
        mobileLabel: 'Day Range',
      },
      { id: 'dq', header: 'Delivery Qty', help: 'Number of shares actually taken to delivery (not intraday-squared) that session.', render: (r) => qty(r.deliveryQty), mobileLabel: 'Delivery Qty' },
      { id: 'ttq', header: 'Total Traded Qty', help: 'Total shares traded in the session, including intraday turns.', render: (r) => qty(r.totalTradedQty), mobileLabel: 'Total Traded Qty' },
    ],
    [],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="Delivery Data"
        help="Shows a single stock's daily delivery percentage and quantity over recent sessions — a high, rising delivery % signals strong-hand (institutional) conviction rather than intraday churn."
        subtitle="Daily delivery % & quantity for a stock (high % delivery = institutional conviction)"
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          placeholder="Symbol (e.g. AXISBANK)"
          aria-label="Stock symbol"
          title="Type an NSE stock symbol (e.g. AXISBANK) and press Go to load its delivery data"
          className="h-9 w-full sm:w-48 rounded-md border border-ay-border bg-surface-1 px-2 text-sm uppercase text-ay-text outline-none focus:border-accent"
        />
        <Select
          value={String(days)}
          options={PERIODS}
          onChange={(v) => setDays(Number(v))}
          ariaLabel="Period"
          title="How many recent trading sessions to show"
        />
        <GoButton onClick={submit} loading={q.isFetching} />
        {data && <span className="text-xs text-ay-muted">{data.symbol}</span>}
      </div>

      {symbol == null ? (
        <EmptyCard icon={Search} title="Enter a stock symbol and press Go." />
      ) : (
        <QueryState
          query={q}
          isEmpty={(d) => (d.items?.length ?? 0) === 0}
          empty={{ title: `No EQ bhavcopy for ${symbol}.` }}
          errorTitle="Couldn't load delivery data"
          skeleton={<Skeleton variant="table-rows" rows={8} cols={9} />}
        >
          {(d) => (
            <BeatBlock>
              <DataTable
                columns={columns}
                rows={d.items ?? []}
                rowKey={(r) => r.date}
                pageSize={30}
                ariaLabel={`${symbol} daily delivery data`}
                emptyMessage={`No EQ bhavcopy for ${symbol}.`}
              />
            </BeatBlock>
          )}
        </QueryState>
      )}
    </LoadBeat>
  );
}

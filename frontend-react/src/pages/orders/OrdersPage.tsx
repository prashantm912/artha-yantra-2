import { useMemo } from 'react';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import {
  useFunds,
  useOrderbook,
  usePositions,
  useTradebook,
  type Funds,
  type OrderbookEntry,
  type PositionEntry,
  type TradebookEntry,
} from '../../api/orders.ts';

// /orders read surface (§18.1 / Phase-4b): the live broker's Orderbook · Positions · Tradebook · Funds,
// four read-only tables. Read-only by design — no place/modify/cancel controls here (the order-WRITE
// path stays separately gated). Decimals format via lib/decimal (never parseFloat). With no broker
// wired the BE returns empty lists + a NOT_CONFIGURED funds row, so every section shows a clean empty
// state and the funds card reads "not configured".

const money = (v: string | null | undefined) => (v == null ? '—' : formatDecimal(v, 2));
const toneClass = (v: string | null | undefined) =>
  v == null ? undefined : isNegative(v) ? 'text-bear' : 'text-bull';

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mb-6">
      <h2 className="mb-2 text-sm font-semibold text-ay-text">{title}</h2>
      {children}
    </section>
  );
}

function FundsCard({ funds }: { funds: Funds | undefined }) {
  if (!funds) return null;
  if (funds.status !== 'OK') {
    return (
      <p className="rounded border border-ay-border bg-surface-1 p-3 text-sm text-ay-muted">
        Funds not configured — no broker is wired for order reads.
      </p>
    );
  }
  const rows: { label: string; value: string | null; tone?: boolean }[] = [
    { label: 'Available Cash', value: funds.availableCash },
    { label: 'Utilised Debit', value: funds.utilisedDebits },
    { label: 'Collateral', value: funds.collateral },
  ];
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
      {rows.map((r) => (
        <div key={r.label} className="rounded border border-ay-border bg-surface-1 p-3 tabular-nums">
          <dt className="text-xs text-ay-muted">{r.label}</dt>
          <dd className={cn('text-lg font-bold', r.tone && toneClass(r.value))}>{money(r.value)}</dd>
        </div>
      ))}
    </dl>
  );
}

export function OrdersPage() {
  const orderbook = useOrderbook();
  const positions = usePositions();
  const tradebook = useTradebook();
  const funds = useFunds();

  const orderColumns = useMemo<DataColumn<OrderbookEntry>[]>(
    () => [
      { id: 'symbol', header: 'Symbol', align: 'left', sortValue: (r) => r.symbol, sortType: 'text', render: (r) => r.symbol, mobileLabel: 'Symbol' },
      { id: 'action', header: 'Action', align: 'left', sortValue: (r) => r.action, sortType: 'text', render: (r) => r.action, mobileLabel: 'Action' },
      { id: 'qty', header: 'Qty', sortValue: (r) => r.qty, sortType: 'decimal', render: (r) => money(r.qty), mobileLabel: 'Qty' },
      { id: 'price', header: 'Price', sortValue: (r) => r.price, sortType: 'decimal', render: (r) => money(r.price), mobileLabel: 'Price' },
      { id: 'pricetype', header: 'Type', align: 'left', sortValue: (r) => r.pricetype, sortType: 'text', render: (r) => r.pricetype, mobileLabel: 'Type' },
      { id: 'status', header: 'Status', align: 'left', sortValue: (r) => r.status, sortType: 'text', render: (r) => r.status, mobileLabel: 'Status' },
      { id: 'timestamp', header: 'Time', align: 'left', sortValue: (r) => r.timestamp, sortType: 'text', render: (r) => r.timestamp, mobileLabel: 'Time' },
    ],
    [],
  );

  const positionColumns = useMemo<DataColumn<PositionEntry>[]>(
    () => [
      { id: 'symbol', header: 'Symbol', align: 'left', sortValue: (r) => r.symbol, sortType: 'text', render: (r) => r.symbol, mobileLabel: 'Symbol' },
      { id: 'side', header: 'Side', align: 'left', sortValue: (r) => r.side, sortType: 'text', render: (r) => r.side, mobileLabel: 'Side' },
      { id: 'qty', header: 'Qty', sortValue: (r) => r.qty, sortType: 'decimal', render: (r) => money(r.qty), mobileLabel: 'Qty' },
      { id: 'avgPrice', header: 'Avg Price', sortValue: (r) => r.avgPrice, sortType: 'decimal', render: (r) => money(r.avgPrice), mobileLabel: 'Avg Price' },
      { id: 'ltp', header: 'LTP', sortValue: (r) => r.ltp, sortType: 'decimal', render: (r) => money(r.ltp), mobileLabel: 'LTP' },
      {
        id: 'mtmPnl',
        header: 'MTM P&L',
        sortValue: (r) => r.mtmPnl,
        sortType: 'decimal',
        render: (r) => money(r.mtmPnl),
        cellClassName: (r) => toneClass(r.mtmPnl) ?? '',
        mobileLabel: 'MTM P&L',
      },
    ],
    [],
  );

  const tradeColumns = useMemo<DataColumn<TradebookEntry>[]>(
    () => [
      { id: 'symbol', header: 'Symbol', align: 'left', sortValue: (r) => r.symbol, sortType: 'text', render: (r) => r.symbol, mobileLabel: 'Symbol' },
      { id: 'action', header: 'Action', align: 'left', sortValue: (r) => r.action, sortType: 'text', render: (r) => r.action, mobileLabel: 'Action' },
      { id: 'qty', header: 'Qty', sortValue: (r) => r.qty, sortType: 'decimal', render: (r) => money(r.qty), mobileLabel: 'Qty' },
      { id: 'price', header: 'Price', sortValue: (r) => r.price, sortType: 'decimal', render: (r) => money(r.price), mobileLabel: 'Price' },
      { id: 'tradeTime', header: 'Trade Time', align: 'left', sortValue: (r) => r.tradeTime, sortType: 'text', render: (r) => r.tradeTime, mobileLabel: 'Trade Time' },
    ],
    [],
  );

  return (
    <div>
      <h1 className="mb-3 text-center text-sm font-semibold text-ay-text">Orders</h1>

      <Section title="Funds">
        {funds.isLoading ? <p className="text-sm text-ay-muted">Loading…</p> : <FundsCard funds={funds.data} />}
      </Section>

      <Section title="Orderbook">
        <DataTable
          columns={orderColumns}
          rows={orderbook.data ?? []}
          rowKey={(r) => r.orderId || `${r.symbol}-${r.timestamp}`}
          emptyMessage="No orders."
          ariaLabel="Orderbook"
        />
      </Section>

      <Section title="Positions">
        <DataTable
          columns={positionColumns}
          rows={positions.data ?? []}
          rowKey={(r) => `${r.exchange}:${r.symbol}:${r.product}`}
          emptyMessage="No open positions."
          ariaLabel="Positions"
        />
      </Section>

      <Section title="Tradebook">
        <DataTable
          columns={tradeColumns}
          rows={tradebook.data ?? []}
          rowKey={(r) => `${r.orderId}-${r.tradeTime}-${r.symbol}`}
          emptyMessage="No trades."
          ariaLabel="Tradebook"
        />
      </Section>
    </div>
  );
}

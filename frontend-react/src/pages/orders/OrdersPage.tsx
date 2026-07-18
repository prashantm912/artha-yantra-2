import { useMemo } from 'react';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { addDecimal, compareDecimal, formatDecimal, isNegative, multiplyByInt } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { FIELD_HELP } from '../../core/fieldHelp.ts';
import { BeatStrip, BeatItem, LoadBeat } from '../../components/LoadBeat.tsx';
import { OrderPrefillTicket } from './OrderPrefillTicket.tsx';
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
// Revamp rollout (Trading screens): visible signature H1 via PageHeader (text preserved), elevated
// funds cards (card shadow-e1 + uppercase wide-tracked caption labels, sign-aware mono values). The
// four read-only DataTables keep their own empty states + selectors untouched.

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
        <div key={r.label} className="card shadow-e1 nums">
          <dt className="text-caption uppercase tracking-wide text-ay-muted">{r.label}</dt>
          <dd className={cn('mt-1 text-lg font-bold', r.tone && toneClass(r.value))}>{money(r.value)}</dd>
        </div>
      ))}
    </dl>
  );
}

// Client-side P&L summary derived from the positions read model + funds snapshot — no extra fetch,
// no backend change. Decimals stay exact (addDecimal / multiplyByInt / compareDecimal, never
// parseFloat). With no broker wired the positions list is empty → every figure is "0" (no NaN, no
// divide-by-zero) and the strip still renders alongside the page's existing empty states.
interface PnlSummary {
  totalMtm: string; // signed
  openCount: number;
  longCount: number;
  shortCount: number;
  netExposure: string; // abs(long + short)
  longExposure: string;
  shortExposure: string;
}

const ZERO = '0';
const isLong = (side: string) => side.toUpperCase() === 'BUY';
const isShort = (side: string) => side.toUpperCase() === 'SELL';

function summarize(positions: PositionEntry[]): PnlSummary {
  let totalMtm = ZERO;
  let longExposure = ZERO;
  let shortExposure = ZERO;
  let longCount = 0;
  let shortCount = 0;

  for (const p of positions) {
    if (p.mtmPnl != null) totalMtm = addDecimal(totalMtm, p.mtmPnl);
    // qty is signed; exposure = |qty| * ltp, bucketed by the derived side.
    if (p.ltp != null) {
      const qty = Math.abs(Math.trunc(Number(p.qty)));
      const exposure = multiplyByInt(p.ltp, qty);
      if (isLong(p.side)) longExposure = addDecimal(longExposure, exposure);
      else if (isShort(p.side)) shortExposure = addDecimal(shortExposure, exposure);
    }
    if (isLong(p.side)) longCount += 1;
    else if (isShort(p.side)) shortCount += 1;
  }

  return {
    totalMtm,
    openCount: positions.length,
    longCount,
    shortCount,
    netExposure: addDecimal(longExposure, shortExposure),
    longExposure,
    shortExposure,
  };
}

/** One P&L stat tile — uppercase wide-tracked caption label + mono value, optional sign-tone. */
function PnlStat({ label, value, tone }: { label: string; value: string; tone?: string | null }) {
  return (
    <div className="card shadow-e1">
      <div className="text-caption uppercase tracking-wide text-ay-muted">{label}</div>
      <div className={cn('mt-1 text-h3 nums font-semibold text-ay-text', tone && toneClass(tone))}>{value}</div>
    </div>
  );
}

function PnlStrip({
  positions,
  funds,
}: {
  positions: PositionEntry[];
  funds: Funds | undefined;
}) {
  const s = useMemo(() => summarize(positions), [positions]);
  const fundsOk = funds?.status === 'OK';
  // compareDecimal so a "0.00"-formatted total still tones neutral (no false bear on -0).
  const mtmTone = compareDecimal(s.totalMtm, ZERO) === 0 ? null : s.totalMtm;

  return (
    <BeatStrip
      className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5"
      data-testid="orders-pnl-strip"
    >
      <BeatItem>
        <PnlStat label="Total MTM P&L" value={money(s.totalMtm)} tone={mtmTone} />
      </BeatItem>
      <BeatItem>
        <PnlStat label="Open Positions" value={`${s.openCount}`} />
      </BeatItem>
      <BeatItem>
        <PnlStat label="Long / Short" value={`${s.longCount} / ${s.shortCount}`} />
      </BeatItem>
      <BeatItem>
        <PnlStat label="Net Exposure" value={money(s.netExposure)} />
      </BeatItem>
      <BeatItem>
        <PnlStat
          label="Avail / Utilised Cash"
          value={fundsOk ? `${money(funds?.availableCash)} / ${money(funds?.utilisedDebits)}` : '—'}
        />
      </BeatItem>
    </BeatStrip>
  );
}

export function OrdersPage() {
  const orderbook = useOrderbook();
  const positions = usePositions();
  const tradebook = useTradebook();
  const funds = useFunds();

  const orderColumns = useMemo<DataColumn<OrderbookEntry>[]>(
    () => [
      { id: 'symbol', header: 'Symbol', align: 'left', sortValue: (r) => r.symbol, sortType: 'text', render: (r) => r.symbol, mobileLabel: 'Symbol', help: 'The instrument this order is for.' },
      { id: 'action', header: 'Action', align: 'left', sortValue: (r) => r.action, sortType: 'text', render: (r) => r.action, mobileLabel: 'Action', help: 'Whether the order buys or sells.' },
      { id: 'qty', header: 'Qty', sortValue: (r) => r.qty, sortType: 'decimal', render: (r) => money(r.qty), mobileLabel: 'Qty', help: 'Order quantity (number of shares/lots).' },
      { id: 'price', header: 'Price', sortValue: (r) => r.price, sortType: 'decimal', render: (r) => money(r.price), mobileLabel: 'Price', help: 'The order price (the limit price, or 0 for a market order).' },
      { id: 'pricetype', header: 'Type', align: 'left', sortValue: (r) => r.pricetype, sortType: 'text', render: (r) => r.pricetype, mobileLabel: 'Type', help: 'Order type — MARKET, LIMIT, SL or SL-M.' },
      { id: 'status', header: 'Status', align: 'left', sortValue: (r) => r.status, sortType: 'text', render: (r) => r.status, mobileLabel: 'Status', help: 'Current order state — open, complete, rejected or cancelled.' },
      { id: 'timestamp', header: 'Time', align: 'left', sortValue: (r) => r.timestamp, sortType: 'text', render: (r) => r.timestamp, mobileLabel: 'Time', help: 'When the order was placed or last updated.' },
    ],
    [],
  );

  const positionColumns = useMemo<DataColumn<PositionEntry>[]>(
    () => [
      { id: 'symbol', header: 'Symbol', align: 'left', sortValue: (r) => r.symbol, sortType: 'text', render: (r) => r.symbol, mobileLabel: 'Symbol', help: 'The instrument held in this position.' },
      { id: 'side', header: 'Side', align: 'left', sortValue: (r) => r.side, sortType: 'text', render: (r) => r.side, mobileLabel: 'Side', help: 'Long (BUY) or short (SELL) direction of the position.' },
      { id: 'qty', header: 'Qty', sortValue: (r) => r.qty, sortType: 'decimal', render: (r) => money(r.qty), mobileLabel: 'Qty', help: 'Net quantity held (shares/lots).' },
      { id: 'avgPrice', header: 'Avg Price', sortValue: (r) => r.avgPrice, sortType: 'decimal', render: (r) => money(r.avgPrice), mobileLabel: 'Avg Price', help: 'Average price at which the position was built.' },
      { id: 'ltp', header: 'LTP', sortValue: (r) => r.ltp, sortType: 'decimal', render: (r) => money(r.ltp), mobileLabel: 'LTP', help: FIELD_HELP.ltp },
      {
        id: 'mtmPnl',
        header: 'MTM P&L',
        sortValue: (r) => r.mtmPnl,
        sortType: 'decimal',
        render: (r) => money(r.mtmPnl),
        cellClassName: (r) => toneClass(r.mtmPnl) ?? '',
        mobileLabel: 'MTM P&L',
        help: 'Mark-to-market profit/loss — the unrealised gain or loss at the current price.',
      },
    ],
    [],
  );

  const tradeColumns = useMemo<DataColumn<TradebookEntry>[]>(
    () => [
      { id: 'symbol', header: 'Symbol', align: 'left', sortValue: (r) => r.symbol, sortType: 'text', render: (r) => r.symbol, mobileLabel: 'Symbol', help: 'The instrument that traded.' },
      { id: 'action', header: 'Action', align: 'left', sortValue: (r) => r.action, sortType: 'text', render: (r) => r.action, mobileLabel: 'Action', help: 'Whether this fill was a buy or a sell.' },
      { id: 'qty', header: 'Qty', sortValue: (r) => r.qty, sortType: 'decimal', render: (r) => money(r.qty), mobileLabel: 'Qty', help: 'Quantity filled in this trade.' },
      { id: 'price', header: 'Price', sortValue: (r) => r.price, sortType: 'decimal', render: (r) => money(r.price), mobileLabel: 'Price', help: 'The price at which this trade was executed.' },
      { id: 'tradeTime', header: 'Trade Time', align: 'left', sortValue: (r) => r.tradeTime, sortType: 'text', render: (r) => r.tradeTime, mobileLabel: 'Trade Time', help: 'When the trade was executed.' },
    ],
    [],
  );

  return (
    <LoadBeat>
      <PageHeader title="Orders" subtitle="Live broker read surface — Orderbook · Positions · Tradebook · Funds" help="A read-only view of your live broker account: pending/filled orders, open positions, executed trades and available funds." />

      {/* §18.4 deep-link target: a scalp-alert push pre-fills a paper ticket here. Renders only when the
          URL carries prefill params, so the plain read surface is unchanged when reached from the nav. */}
      <OrderPrefillTicket />

      {/* The P&L strip summarises the positions read model client-side; suppress it when that fetch
          ERRORED so it can't paint a misleading all-zero "no positions" summary — the Positions
          section below carries the authoritative error state. */}
      {!positions.isError && <PnlStrip positions={positions.data ?? []} funds={funds.data} />}

      {/* Each read surface routes through the shared QueryState wrapper so a 500 renders the error
          card (role=alert) instead of a false-empty table — error ≠ empty. `isEmpty` stays false so
          a genuinely empty (but successful) response falls through to the DataTable's own empty
          message / the funds "not configured" notice, preserving the no-broker-wired UX. */}
      <Section title="Funds">
        <QueryState
          query={funds}
          isEmpty={() => false}
          errorTitle="Couldn't load funds"
          skeleton={<Skeleton variant="card" className="rounded border border-ay-border p-3" />}
        >
          {(data) => <FundsCard funds={data} />}
        </QueryState>
      </Section>

      <Section title="Orderbook">
        <QueryState
          query={orderbook}
          isEmpty={() => false}
          errorTitle="Couldn't load the orderbook"
          skeleton={<Skeleton variant="table-rows" rows={4} cols={7} className="rounded-lg border border-ay-border p-2" />}
        >
          {(rows) => (
            <DataTable
              columns={orderColumns}
              rows={rows}
              rowKey={(r) => r.orderId || `${r.symbol}-${r.timestamp}`}
              emptyMessage="No orders."
              ariaLabel="Orderbook"
            />
          )}
        </QueryState>
      </Section>

      <Section title="Positions">
        <QueryState
          query={positions}
          isEmpty={() => false}
          errorTitle="Couldn't load positions"
          skeleton={<Skeleton variant="table-rows" rows={4} cols={6} className="rounded-lg border border-ay-border p-2" />}
        >
          {(rows) => (
            <DataTable
              columns={positionColumns}
              rows={rows}
              rowKey={(r) => `${r.exchange}:${r.symbol}:${r.product}`}
              emptyMessage="No open positions."
              ariaLabel="Positions"
            />
          )}
        </QueryState>
      </Section>

      <Section title="Tradebook">
        <QueryState
          query={tradebook}
          isEmpty={() => false}
          errorTitle="Couldn't load the tradebook"
          skeleton={<Skeleton variant="table-rows" rows={4} cols={5} className="rounded-lg border border-ay-border p-2" />}
        >
          {(rows) => (
            <DataTable
              columns={tradeColumns}
              rows={rows}
              rowKey={(r) => `${r.orderId}-${r.tradeTime}-${r.symbol}`}
              emptyMessage="No trades."
              ariaLabel="Tradebook"
            />
          )}
        </QueryState>
      </Section>
    </LoadBeat>
  );
}

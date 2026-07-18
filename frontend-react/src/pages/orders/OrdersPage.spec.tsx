import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Funds, OrderbookEntry, PositionEntry, TradebookEntry } from '../../api/orders.ts';

// Mockable hook returns — the four queries the page consumes. Each test overrides via the vi.mock factory
// state below so we can exercise both the populated tables and the empty / NOT_CONFIGURED states.
const state: {
  orderbook: OrderbookEntry[];
  positions: PositionEntry[];
  tradebook: TradebookEntry[];
  funds: Funds | undefined;
  positionsError: boolean;
} = { orderbook: [], positions: [], tradebook: [], funds: undefined, positionsError: false };

// An errored query is what the QueryState wrapper keys off (isError → the shared error card); a
// success is the plain `{ data }`. FE-04: on a positions 500 the page must NOT paint a false-empty.
const errored = () => ({ isError: true, refetch: vi.fn() });

vi.mock('../../api/orders.ts', () => ({
  useOrderbook: () => ({ data: state.orderbook, isLoading: false }),
  usePositions: () => (state.positionsError ? errored() : { data: state.positions, isLoading: false }),
  useTradebook: () => ({ data: state.tradebook, isLoading: false }),
  useFunds: () => ({ data: state.funds, isLoading: false }),
}));

import { OrdersPage } from './OrdersPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <OrdersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('OrdersPage', () => {
  beforeEach(() => {
    state.positionsError = false;
  });

  it('renders the four read-only tables with faithful columns and rows', () => {
    state.orderbook = [
      {
        symbol: 'INFY', exchange: 'NSE', action: 'SELL', qty: '5', price: '1500', triggerPrice: '0',
        pricetype: 'LIMIT', product: 'MIS', orderId: '24120900000213', status: 'complete',
        timestamp: '09-Dec-2024 09:44:09',
      },
    ];
    state.positions = [
      {
        symbol: 'NIFTY24JUN24000CE', exchange: 'NFO', side: 'BUY', qty: '75', product: 'MIS',
        avgPrice: '120.50', ltp: '135.00', mtmPnl: '1087.50',
      },
    ];
    state.tradebook = [
      {
        symbol: 'INFY', exchange: 'NSE', action: 'BUY', qty: '1', price: '1914.40', tradeValue: '1914.40',
        product: 'MIS', orderId: '24120900009388', tradeTime: '09-Dec-2024 09:16:48',
      },
    ];
    state.funds = {
      status: 'OK', availableCash: '18083.01', collateral: '0.00', m2mRealized: '0.00',
      m2mUnrealized: '0.00', utilisedDebits: '500.00',
    };

    renderPage();

    // Orderbook table columns + a row.
    const orderbook = screen.getByRole('region', { name: 'Orderbook' });
    for (const h of ['Symbol', 'Action', 'Qty', 'Price', 'Type', 'Status', 'Time']) {
      expect(within(orderbook).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    expect(within(orderbook).getByText('complete')).toBeInTheDocument();

    // Positions table columns + the derived BUY side + MTM.
    const positions = screen.getByRole('region', { name: 'Positions' });
    for (const h of ['Symbol', 'Side', 'Qty', 'Avg Price', 'LTP', 'MTM P&L']) {
      expect(within(positions).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    expect(within(positions).getByText('1087.50')).toBeInTheDocument();

    // Tradebook table columns.
    const tradebook = screen.getByRole('region', { name: 'Tradebook' });
    for (const h of ['Symbol', 'Action', 'Qty', 'Price', 'Trade Time']) {
      expect(within(tradebook).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }

    // Funds card surfaces available cash.
    expect(screen.getByText('Available Cash')).toBeInTheDocument();
    expect(screen.getByText('18083.01')).toBeInTheDocument();

    // P&L strip summarises the positions + funds client-side: total MTM, open count, long/short split.
    const strip = screen.getByTestId('orders-pnl-strip');
    expect(within(strip).getByText('Total MTM P&L')).toBeInTheDocument();
    expect(within(strip).getByText('1087.50')).toBeInTheDocument(); // sum of the single position's MTM
    expect(within(strip).getByText('1 / 0')).toBeInTheDocument(); // one long, no short
    expect(within(strip).getByText('10125.00')).toBeInTheDocument(); // net exposure = 75 * 135.00
  });

  it('shows empty states and a not-configured funds notice when nothing is wired', () => {
    state.orderbook = [];
    state.positions = [];
    state.tradebook = [];
    state.funds = {
      status: 'NOT_CONFIGURED', availableCash: null, collateral: null, m2mRealized: null,
      m2mUnrealized: null, utilisedDebits: null,
    };

    renderPage();

    // DataTable renders the empty message in both the desktop table and the mobile card list.
    expect(screen.getAllByText('No orders.').length).toBeGreaterThan(0);
    expect(screen.getAllByText('No open positions.').length).toBeGreaterThan(0);
    expect(screen.getAllByText('No trades.').length).toBeGreaterThan(0);
    expect(screen.getByText(/Funds not configured/)).toBeInTheDocument();

    // P&L strip degrades to zeros/dashes on empty positions + NOT_CONFIGURED funds (no NaN).
    const strip = screen.getByTestId('orders-pnl-strip');
    expect(within(strip).queryByText(/NaN/)).not.toBeInTheDocument();
    expect(within(strip).getAllByText('0.00').length).toBe(2); // total MTM + net exposure both 0.00
    expect(within(strip).getByText('0 / 0')).toBeInTheDocument(); // no longs / shorts
    expect(within(strip).getByText('—')).toBeInTheDocument(); // funds dash when not configured
  });

  it('renders an error state (not a false-empty) when the positions fetch 500s (FE-04)', () => {
    state.orderbook = [];
    state.tradebook = [];
    state.funds = { status: 'OK', availableCash: '100', collateral: '0', m2mRealized: '0', m2mUnrealized: '0', utilisedDebits: '0' };
    state.positionsError = true;

    renderPage();

    // Positions renders the shared error card (role=alert), NOT the "No open positions." empty
    // message — and it's the only errored query, so exactly one error card shows.
    expect(screen.getByTestId('qs-error')).toBeInTheDocument();
    expect(screen.getByText('Couldn\'t load positions')).toBeInTheDocument();
    expect(screen.queryByText('No open positions.')).not.toBeInTheDocument();
    // The healthy orderbook/tradebook still show their genuine empty messages (empty ≠ error).
    expect(screen.getAllByText('No orders.').length).toBeGreaterThan(0);
    // The client-side P&L summary strip is suppressed on a positions error — no misleading zero book.
    expect(screen.queryByTestId('orders-pnl-strip')).not.toBeInTheDocument();
  });
});

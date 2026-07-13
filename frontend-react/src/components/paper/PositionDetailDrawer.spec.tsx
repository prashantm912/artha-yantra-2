import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { PaperEvent, PaperPositionDetail } from '../../api/paper.ts';

const { mockUsePaperEvents, mockUsePaperEventsLive, mockUsePaperPositionDetail } = vi.hoisted(
  () => ({
    mockUsePaperEvents: vi.fn(),
    mockUsePaperEventsLive: vi.fn(),
    mockUsePaperPositionDetail: vi.fn(),
  }),
);

vi.mock('../../api/paper.ts', () => ({
  bookLabel: (book: string) => book,
  useEditBrackets: () => ({ mutate: vi.fn(), isPending: false, isError: false }),
  usePaperEvents: mockUsePaperEvents,
  usePaperEventsLive: mockUsePaperEventsLive,
  usePaperPositionDetail: mockUsePaperPositionDetail,
}));

import { PositionDetailDrawer } from './PositionDetailDrawer.tsx';

const detail: PaperPositionDetail = {
  id: 42,
  book: 'scalper',
  exchange: 'NFO',
  tradingsymbol: 'NIFTY26JUL25000CE',
  side: 'BUY',
  qty: 75,
  avgEntryPrice: '100.00',
  markPrice: '101.50',
  unrealizedPnl: null,
  realizedPnl: '125.50',
  status: 'CLOSED',
  openedAt: '2026-07-14T09:15:00+05:30',
  closedAt: '2026-07-14T09:45:00+05:30',
  closeReason: 'TAKE_PROFIT',
  stopLoss: '90.00',
  takeProfit: '110.00',
  advisedLots: 1,
  marginSnapshot: '7500.00',
  marginPct: '0.75',
  subaccountIdx: null,
  openingSignalId: null,
  openingSignal: null,
  orders: [],
};

const events: PaperEvent[] = [
  {
    id: 3,
    positionId: 42,
    kind: 'CLOSED',
    book: 'scalper',
    exchange: 'NFO',
    tradingsymbol: 'NIFTY26JUL25000CE',
    side: 'BUY',
    qty: 75,
    reason: 'TAKE_PROFIT',
    realizedPnl: '125.50',
    createdAt: '2026-07-14T09:45:00+05:30',
  },
  {
    id: 2,
    positionId: 42,
    kind: 'BRACKET_HIT',
    book: 'scalper',
    exchange: 'NFO',
    tradingsymbol: 'NIFTY26JUL25000CE',
    side: 'BUY',
    qty: 75,
    reason: 'TAKE_PROFIT',
    realizedPnl: null,
    createdAt: '2026-07-14T09:44:30+05:30',
  },
  {
    id: 1,
    positionId: 42,
    kind: 'OPENED',
    book: 'scalper',
    exchange: 'NFO',
    tradingsymbol: 'NIFTY26JUL25000CE',
    side: 'BUY',
    qty: 75,
    reason: null,
    realizedPnl: null,
    createdAt: '2026-07-14T09:15:00+05:30',
  },
];

function renderDrawer() {
  return render(
    <MemoryRouter>
      <PositionDetailDrawer positionId={42} open onOpenChange={vi.fn()} />
    </MemoryRouter>,
  );
}

describe('PositionDetailDrawer order events', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUsePaperPositionDetail.mockReturnValue({
      data: detail,
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    });
    mockUsePaperEvents.mockReturnValue({
      data: { items: events },
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    });
  });

  it('renders lifecycle events oldest to newest with IST timestamps and event details', () => {
    renderDrawer();

    const timeline = screen.getByRole('list', { name: 'Order events' });
    const items = within(timeline).getAllByRole('listitem');

    expect(items).toHaveLength(3);
    expect(within(items[0]).getByText('Opened')).toBeInTheDocument();
    expect(within(items[0]).getByText(/09:15:00 IST/)).toBeInTheDocument();
    expect(within(items[1]).getByText('Bracket hit')).toBeInTheDocument();
    expect(within(items[1]).getByText('TAKE_PROFIT')).toBeInTheDocument();
    expect(within(items[2]).getByText('Closed')).toBeInTheDocument();
    expect(within(items[2]).getByText('Realized P&L 125.50')).toBeInTheDocument();
  });

  it('renders a friendly empty state when the position has no events', () => {
    mockUsePaperEvents.mockReturnValue({
      data: { items: [] },
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    });

    renderDrawer();

    expect(screen.getByText('No events recorded for this position yet.')).toBeInTheDocument();
  });

  it('announces the loading state', () => {
    mockUsePaperEvents.mockReturnValue({
      data: undefined,
      isPending: true,
      isError: false,
      refetch: vi.fn(),
    });

    renderDrawer();

    expect(screen.getByRole('status')).toHaveTextContent('Loading order events…');
  });

  it('announces an event-load error without crashing the drawer', () => {
    mockUsePaperEvents.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    });

    renderDrawer();

    expect(screen.getByRole('alert')).toHaveTextContent("Couldn't load order events.");
  });
});

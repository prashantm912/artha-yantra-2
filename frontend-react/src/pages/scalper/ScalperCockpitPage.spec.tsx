import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const place = vi.fn();
const close = vi.fn();

vi.mock('../../api/signals.ts', () => ({
  useSignals: () => ({
    data: {
      items: [
        { id: 7, exchange: 'NSE', tradingsymbol: 'RELIANCE', side: 'BUY', signalType: 'ENTRY', status: 'ACTIVE', generatedAt: '2026-06-23T09:30:00+05:30', suggestedQty: '50', entryPrice: '1300.00' },
      ],
    },
  }),
  useSignalsLive: () => {},
  useSignalDetail: () => ({ data: undefined }),
}));
vi.mock('../../api/paper.ts', () => ({
  usePaperPositions: () => ({ data: { items: [{ id: 3, exchange: 'NSE', tradingsymbol: 'INFY', side: 'SELL', qty: 100, avgEntryPrice: '1500.00', markPrice: '1490.00', unrealizedPnl: '1000.00' }] } }),
  usePaperAccount: () => ({ data: { equity: '1010000.00', dayPnl: '2500.00' } }),
  usePlacePaperOrder: () => ({ mutate: place, isPending: false }),
  useClosePosition: () => ({ mutate: close }),
}));
vi.mock('../../api/instruments.ts', () => ({
  useUnderlyings: () => ({ data: ['NIFTY 50', 'NIFTY BANK'] }),
  useExpiries: () => ({ data: [] }),
}));
vi.mock('../../api/ticks.ts', () => ({ useLiveTicks: () => ({}) }));
// Each leg carries its own `exchange` — the instrument master's, as market-data publishes it. The
// page must READ that value; there is deliberately no `optionExchange` helper to mock any more,
// because guessing the exchange off the underlying's name is what this ticket used to do.
const chainRows = {
  current: [
    {
      strike: '24000.00',
      ce: { exchange: 'NFO', tradingsymbol: 'NIFTY24JUN24000CE', ltp: '120.00', oi: 1000 },
      pe: { exchange: 'NFO', tradingsymbol: 'NIFTY24JUN24000PE', ltp: '100.00', oi: 900 },
    },
  ] as Array<Record<string, unknown>>,
};
vi.mock('../../api/scalper.ts', () => ({
  useOptionChain: () => ({
    data: { underlying: 'NIFTY 50', spot: '24000.00', rows: chainRows.current },
    isLoading: false,
  }),
  useLatestTick: () => ({ data: null }),
}));

import { ScalperCockpitPage } from './ScalperCockpitPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ScalperCockpitPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const DEFAULT_ROWS = [
  {
    strike: '24000.00',
    ce: { exchange: 'NFO', tradingsymbol: 'NIFTY24JUN24000CE', ltp: '120.00', oi: 1000 },
    pe: { exchange: 'NFO', tradingsymbol: 'NIFTY24JUN24000PE', ltp: '100.00', oi: 900 },
  },
];

describe('ScalperCockpitPage', () => {
  // The chain is a module-level mock the later cases rewrite, so restore it per test rather than
  // leaving the file order-dependent.
  beforeEach(() => {
    chainRows.current = DEFAULT_ROWS.map((r) => ({ ...r }));
  });

  it('loads the ticket from a signal, places a paper order, and shows positions', () => {
    renderPage();
    // signal feed + open position
    expect(screen.getByText('NSE:RELIANCE')).toBeInTheDocument();
    expect(screen.getByText('NSE:INFY')).toBeInTheDocument();

    // click the signal → ticket pre-fills
    fireEvent.click(screen.getByText('NSE:RELIANCE'));
    expect((screen.getByLabelText('Instrument') as HTMLInputElement).value).toBe('NSE:RELIANCE');

    // place the paper order (BUY)
    fireEvent.click(screen.getByRole('button', { name: /Place paper BUY/ }));
    expect(place).toHaveBeenCalledWith(
      expect.objectContaining({ signalId: 7, exchange: 'NSE', tradingsymbol: 'RELIANCE', side: 'BUY', qty: 50 }),
      expect.anything(),
    );

    // close a position
    fireEvent.click(screen.getByText('Close'));
    expect(close).toHaveBeenCalledWith({ id: 3 });
  });

  it('option quick-pick fills the ticket with the NFO leg + its LTP', () => {
    renderPage();
    fireEvent.click(screen.getByText(/Option quick-pick/));
    // CE leg ltp button → fills the instrument with the NFO option symbol
    fireEvent.click(screen.getByText('120.00'));
    expect((screen.getByLabelText('Instrument') as HTMLInputElement).value).toBe('NFO:NIFTY24JUN24000CE');
    expect((screen.getByLabelText('Price') as HTMLInputElement).value).toBe('120.00');
  });

  // The discriminating case: the leg's exchange CONTRADICTS what a name guess would say. The
  // underlying is "NIFTY 50" and the symbol starts with NIFTY, so the old
  // `/SENSEX|BANKEX/i.test(underlying) ? 'BFO' : 'NFO'` helper returns NFO — this only passes if the
  // ticket reads the leg's own value. It matters because this ticket submits a real paper order: a
  // wrong exchange 404s the instrument lookup into a lot-1 equity proxy and mis-sizes the position.
  it('takes the exchange from the leg, not the underlying name', () => {
    chainRows.current = [
      {
        strike: '24000.00',
        ce: { exchange: 'BFO', tradingsymbol: 'NIFTY24JUN24000CE', ltp: '120.00', oi: 1000 },
        pe: { exchange: 'BFO', tradingsymbol: 'NIFTY24JUN24000PE', ltp: '100.00', oi: 900 },
      },
    ];
    renderPage();
    fireEvent.click(screen.getByText(/Option quick-pick/));
    fireEvent.click(screen.getByText('120.00'));
    expect((screen.getByLabelText('Instrument') as HTMLInputElement).value).toBe('BFO:NIFTY24JUN24000CE');
  });

  // No exchange ⇒ no canonical key ⇒ not pickable. Leaving the instrument blank is what keeps the
  // Place button disabled by its existing ':' guard, so an unkeyed contract cannot be ordered.
  it('refuses to load a leg the instrument master has no exchange for', () => {
    chainRows.current = [
      {
        strike: '24000.00',
        ce: { exchange: null, tradingsymbol: 'NIFTY24JUN24000CE', ltp: '120.00', oi: 1000 },
        pe: { exchange: 'NFO', tradingsymbol: 'NIFTY24JUN24000PE', ltp: '100.00', oi: 900 },
      },
    ];
    renderPage();
    fireEvent.click(screen.getByText(/Option quick-pick/));
    const unkeyed = screen.getByText('120.00');
    expect(unkeyed).toBeDisabled();
    fireEvent.click(unkeyed);
    expect((screen.getByLabelText('Instrument') as HTMLInputElement).value).toBe('');
    // ...while the sibling leg, which does have one, still works.
    fireEvent.click(screen.getByText('100.00'));
    expect((screen.getByLabelText('Instrument') as HTMLInputElement).value).toBe('NFO:NIFTY24JUN24000PE');
  });
});

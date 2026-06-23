import { describe, expect, it, vi } from 'vitest';
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
}));
vi.mock('../../api/paper.ts', () => ({
  usePaperPositions: () => ({ data: { items: [{ id: 3, exchange: 'NSE', tradingsymbol: 'INFY', side: 'SELL', qty: 100, markPrice: '1490.00', unrealizedPnl: '1000.00' }] } }),
  usePaperAccount: () => ({ data: { equity: '1010000.00', dayPnl: '2500.00' } }),
  usePlacePaperOrder: () => ({ mutate: place, isPending: false }),
  useClosePosition: () => ({ mutate: close }),
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

describe('ScalperCockpitPage', () => {
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
});

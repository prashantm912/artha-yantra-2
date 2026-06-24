import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SignalDto } from '../../api/signals.ts';

// The inline "Take (paper)" ticket prefills from the signal and submits through the EXISTING paper
// engine (POST /paper/orders via usePlacePaperOrder), carrying the signal id for provenance. We mock the
// paper mutation + the detail hydrate and assert: (1) the ticket prefills entry/SL/target/qty, (2) Place
// posts the right defined-risk payload, (3) a scalper signal's checklist gates the Place button.

const place = vi.fn();

vi.mock('../../api/paper.ts', () => ({
  usePlacePaperOrder: () => ({ mutate: place, isPending: false }),
}));

const useSignalDetail = vi.fn();
vi.mock('../../api/signals.ts', () => ({
  useSignalDetail: (id: number | null) => useSignalDetail(id),
}));

import { SignalTakeTicket } from './SignalTakeTicket.tsx';

const signal: SignalDto = {
  id: 42,
  exchange: 'NFO',
  tradingsymbol: 'NIFTY24JUN24000CE',
  interval: '3m',
  signalType: 'ENTRY',
  side: 'BUY',
  entryPrice: '120.50',
  stopLoss: '100.00',
  target: '160.00',
  compositeScore: '0.8',
  scoreBreakdown: {} as SignalDto['scoreBreakdown'],
  status: 'ACTIVE',
  generatedAt: '2026-06-24T09:30:00+05:30',
  suggestedQty: '50',
};

function renderTicket(s: SignalDto = signal) {
  const onDone = vi.fn();
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={qc}>
      <SignalTakeTicket signal={s} onDone={onDone} />
    </QueryClientProvider>,
  );
  return { ...utils, onDone };
}

describe('SignalTakeTicket', () => {
  it('prefills from the signal and posts a defined-risk paper order with the signal id', () => {
    useSignalDetail.mockReturnValue({ data: undefined });
    const { onDone } = renderTicket();

    // Prefilled from the signal (qty/SL/target).
    expect((screen.getByLabelText('Qty') as HTMLInputElement).value).toBe('50');
    expect((screen.getByLabelText('Stop loss') as HTMLInputElement).value).toBe('100.00');
    expect((screen.getByLabelText('Take profit') as HTMLInputElement).value).toBe('160.00');

    fireEvent.click(screen.getByRole('button', { name: 'Place paper BUY' }));
    expect(place).toHaveBeenCalledWith(
      {
        signalId: 42,
        exchange: 'NFO',
        tradingsymbol: 'NIFTY24JUN24000CE',
        side: 'BUY',
        qty: 50,
        price: '120.50',
        stopLoss: '100.00',
        takeProfit: '160.00',
      },
      expect.objectContaining({ onSuccess: onDone }),
    );
  });

  it('gates Place behind the manual-verify checklist for a scalper signal', () => {
    useSignalDetail.mockReturnValue({
      data: {
        scalperDetail: {
          side: 'CE',
          underlying: 'NIFTY 50',
          expiry: '2026-06-26',
          tradeable: 'NFO:NIFTY24JUN24000CE',
          strike: 24000,
          option_ltp: 120,
          iv: 12,
          delta: 0.5,
          confluence_aggregate: 0.7,
          dots: [],
          manual_checks: [
            { key: 'trend', label: 'Trend aligned', doc_ref: '', assist: '' },
          ],
        },
      },
    });
    renderTicket();

    // Unconfirmed → Place disabled.
    const placeBtn = screen.getByRole('button', { name: 'Place paper BUY' });
    expect(placeBtn).toBeDisabled();

    // Tick the single manual check → Place enabled.
    fireEvent.click(screen.getByRole('checkbox', { name: /Trend aligned/ }));
    expect(placeBtn).toBeEnabled();
  });

  it('cancels via onDone without placing', () => {
    useSignalDetail.mockReturnValue({ data: undefined });
    const { onDone } = renderTicket();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onDone).toHaveBeenCalled();
  });
});

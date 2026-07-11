import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

// The /orders deep-link pre-fill ticket (§18.4): a scalp-alert push lands here with the leg in the
// URL. We mock the paper mutation and assert: (1) nothing renders without params, (2) the ticket
// prefills the qty and posts the right paper order (signal id + option leg + book), (3) a success
// flips to the placed confirmation.

const place = vi.fn();
vi.mock('../../api/paper.ts', () => ({
  usePlacePaperOrder: () => ({ mutate: place, isPending: false, isError: false }),
}));

import { OrderPrefillTicket } from './OrderPrefillTicket.tsx';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <OrderPrefillTicket />
    </MemoryRouter>,
  );
}

describe('OrderPrefillTicket', () => {
  it('renders nothing without prefill params', () => {
    const { container } = renderAt('/orders');
    expect(container).toBeEmptyDOMElement();
  });

  it('prefills from the deep link and posts a paper order with the signal id + option leg', () => {
    place.mockReset();
    renderAt('/orders?sig=77&symbol=NFO%3ANIFTY24JUN24000CE&side=BUY&qty=75&book=scalper');

    expect((screen.getByLabelText('Qty') as HTMLInputElement).value).toBe('75');

    fireEvent.click(screen.getByRole('button', { name: 'Place paper BUY' }));
    expect(place).toHaveBeenCalledWith(
      {
        signalId: 77,
        exchange: 'NFO',
        tradingsymbol: 'NIFTY24JUN24000CE',
        side: 'BUY',
        qty: 75,
        price: undefined,
        stopLoss: undefined,
        takeProfit: undefined,
        book: 'scalper',
      },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
  });

  it('shows a placed confirmation after a successful fill', () => {
    place.mockReset();
    place.mockImplementation((_body, opts) => opts?.onSuccess?.());
    renderAt('/orders?symbol=NFO%3ANIFTY24JUN24000PE&side=SELL&qty=75');

    fireEvent.click(screen.getByRole('button', { name: 'Place paper SELL' }));
    expect(screen.getByRole('status', { name: 'Paper order placed' })).toBeInTheDocument();
  });
});

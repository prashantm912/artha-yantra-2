import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { EquityDelivery } from '../../api/types.ts';

const delivery: EquityDelivery = {
  symbol: 'AXISBANK',
  days: 15,
  items: [
    {
      date: '2026-06-12',
      open: '1372',
      high: '1378',
      low: '1360.6',
      close: '1368.3',
      ltpChangePct: '0.88',
      deliveryPct: '69.39',
      dayRange: '17.40',
      dayRangePct: '1.27',
      deliveryQty: 5220325,
      totalTradedQty: 7522828,
    },
  ],
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useEquityDelivery: () => ({ data: delivery, isFetching: false, refetch: () => {} }),
}));

import { DeliveryDataPage } from './DeliveryDataPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <DeliveryDataPage />
    </QueryClientProvider>,
  );
}

describe('DeliveryDataPage', () => {
  it('shows a prompt before a symbol is submitted, then the delivery series after Go', () => {
    renderPage();
    expect(screen.getByText(/Enter a stock symbol/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Stock symbol'), { target: { value: 'AXISBANK' } });
    fireEvent.click(screen.getByRole('button', { name: /go/i }));

    const table = screen.getByRole('table');
    for (const h of ['Date', '% LTP Chg', '% Delivery', 'Day Range', 'Delivery Qty']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    expect(screen.getAllByText('2026-06-12').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/69\.39%/).length).toBeGreaterThan(0);
  });
});

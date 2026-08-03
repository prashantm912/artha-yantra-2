import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Breadth } from '../../api/types.ts';

// EChart pulls ResizeObserver (absent in jsdom) — stub it out for the page test.
vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));

const data: Breadth = {
  summary: {
    tradeDate: '2026-06-20',
    advances: 1200,
    declines: 800,
    unchanged: 50,
    total: 2050,
    avgDeliveryPct: '45.50',
  },
  topDelivery: [
    { symbol: 'TCS', deliveryPct: '78.20', close: '3900.00', pctChange: '1.25' },
    { symbol: 'INFY', deliveryPct: '65.10', close: '1500.00', pctChange: '-0.80' },
  ],
  asOf: '2026-06-20T19:30:00Z',
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useBreadth: () => ({ data, isFetching: false, refetch: () => {} }),
  useBreadthHistory: () => ({
    data: { items: [], from: null, to: null },
    isPending: false,
    isError: false,
    isSuccess: true,
  }),
}));

import { BreadthPage } from './BreadthPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <BreadthPage />
    </QueryClientProvider>,
  );
}

describe('BreadthPage', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 4775ms in a full-suite run.
  it('renders the advance/decline summary and delivery-leaders table', () => {
    renderPage();
    // Summary pills.
    expect(screen.getByText('Advances')).toBeInTheDocument();
    expect(screen.getByText('1200')).toBeInTheDocument();
    expect(screen.getByText('800')).toBeInTheDocument();
    // Delivery-leaders columns + a row (DataTable renders desktop + mobile → getAllByText).
    const table = screen.getByRole('table');
    for (const h of ['Symbol', 'Delivery %', 'Close', '% Chg']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    expect(screen.getAllByText('TCS').length).toBeGreaterThan(0);
  }, 15_000);
});

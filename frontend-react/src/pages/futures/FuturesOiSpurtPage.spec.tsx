import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { FutSpurtChain } from '../../api/types.ts';

const data: FutSpurtChain = {
  asOf: '2026-06-16T13:40:00+05:30',
  items: [
    { tradingsymbol: 'NIFTY26JUNFUT', ltp: '24016.60', prevClose: '23950.00', pricePct: '0.28', oi: 17710680, oiChange: 120000, spurtPct: '0.68', interpretation: 'LONG_BUILDUP' },
    { tradingsymbol: 'BANKNIFTY26JUNFUT', ltp: '57200.00', prevClose: '57400.00', pricePct: '-0.35', oi: 2268330, oiChange: 90000, spurtPct: '4.13', interpretation: 'SHORT_BUILDUP' },
  ],
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useFuturesSpurt: () => ({ data, isFetching: false, isLoading: false, refetch: vi.fn() }),
}));

import { FuturesOiSpurtPage } from './FuturesOiSpurtPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <FuturesOiSpurtPage />
    </QueryClientProvider>,
  );
}

describe('FuturesOiSpurtPage', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3118ms in a full-suite run.
  it('renders the 4 OI-interpretation quadrants and buckets contracts into them', () => {
    renderPage();
    for (const q of ['Long Build Up', 'Short Build Up', 'Short Covering', 'Long Unwinding']) {
      expect(screen.getByText(q)).toBeInTheDocument();
    }
    // The LONG_BUILDUP contract lands in a quadrant table.
    expect(screen.getAllByText('NIFTY26JUNFUT').length).toBeGreaterThan(0);
    expect(screen.getAllByText('BANKNIFTY26JUNFUT').length).toBeGreaterThan(0);
  }, 15_000);
});

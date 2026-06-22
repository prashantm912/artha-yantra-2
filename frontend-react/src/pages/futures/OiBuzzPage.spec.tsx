import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { OiBuzzHeatmap } from '../../api/types.ts';

// EChart pulls ResizeObserver (absent in jsdom) — stub it; the test asserts the chrome, not the canvas.
vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));

const heatmap: OiBuzzHeatmap = {
  index: 'NIFTY 50',
  advance: 2,
  decline: 1,
  tiles: [
    { symbol: 'TRENT', changePct: '5.06', ltp: '6500.00', open: '6200', high: '6550', low: '6180', oi: 1200000 },
    { symbol: 'HDFCLIFE', changePct: '4.39', ltp: '720.00', open: '690', high: '725', low: '688', oi: 900000 },
    { symbol: 'ONGC', changePct: '-1.05', ltp: '270.00', open: '273', high: '274', low: '269', oi: 1500000 },
  ],
  asOf: '2026-06-23T12:30:00+05:30',
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useOiBuzzIndices: () => ({ data: ['NIFTY 50', 'NIFTY BANK'] }),
  useOiBuzz: () => ({ data: heatmap, isFetching: false, refetch: () => {} }),
}));

import { OiBuzzPage } from './OiBuzzPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <OiBuzzPage />
    </QueryClientProvider>,
  );
}

describe('OiBuzzPage', () => {
  it('renders the advance/decline split, title and index selector', () => {
    renderPage();
    expect(screen.getByText(/Advance: 2/)).toBeInTheDocument();
    expect(screen.getByText(/Decline: 1/)).toBeInTheDocument();
    expect(screen.getByText(/Oi Buzz \(Change in % wise\)/)).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'NIFTY 50' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search symbol' })).toBeInTheDocument();
  });
});

import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { OpenHighLow } from '../../api/types.ts';

const data: OpenHighLow = {
  index: 'NIFTY 50',
  asOf: '2026-06-23T13:30:00+05:30',
  openHigh: [
    { symbol: 'INFY', dayOpen: '1050.00', dayHigh: '1050.00', dayLow: '1030.00', ltp: '1035.00', farPct: '-1.43' },
  ],
  openLow: [
    { symbol: 'RELIANCE', dayOpen: '1300.00', dayHigh: '1330.00', dayLow: '1300.00', ltp: '1326.00', farPct: '2.00' },
  ],
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useOiBuzzIndices: () => ({ data: ['NIFTY 50', 'NIFTY BANK'] }),
  useOpenHighLow: () => ({ data, isFetching: false, refetch: () => {} }),
}));

import { OpenHighLowPage } from './OpenHighLowPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <OpenHighLowPage />
    </QueryClientProvider>,
  );
}

describe('OpenHighLowPage', () => {
  it('renders the O=H (bearish) and O=L (bullish) mirrored tables', () => {
    renderPage();
    expect(screen.getByText(/Open = High \(bearish\): 1/)).toBeInTheDocument();
    expect(screen.getByText(/Open = Low \(bullish\): 1/)).toBeInTheDocument();
    const tables = screen.getAllByRole('table');
    expect(within(tables[0]).getByRole('columnheader', { name: 'Day High' })).toBeInTheDocument();
    expect(within(tables[1]).getByRole('columnheader', { name: 'Day Low' })).toBeInTheDocument();
    expect(screen.getAllByText('INFY').length).toBeGreaterThan(0);
    expect(screen.getAllByText('RELIANCE').length).toBeGreaterThan(0);
  });
});

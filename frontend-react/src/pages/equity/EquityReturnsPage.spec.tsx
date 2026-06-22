import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { EquityReturns } from '../../api/types.ts';

const data: EquityReturns = {
  asOf: '2026-06-22',
  items: [
    { symbol: 'ONGC', industry: 'Oil Gas & Consumable Fuels', ltp: '270.00', r1d: '-1.05', r1w: '-2.00', r1m: '1.00', r6m: null, r1y: null },
    { symbol: 'TRENT', industry: 'Consumer Services', ltp: '6500.00', r1d: '5.06', r1w: '8.00', r1m: '12.00', r6m: null, r1y: null },
  ],
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useEquityReturns: () => ({ data, isFetching: false, refetch: () => {} }),
}));

import { EquityReturnsPage } from './EquityReturnsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <EquityReturnsPage />
    </QueryClientProvider>,
  );
}

describe('EquityReturnsPage', () => {
  it('renders the multi-window columns, sector, and Current-Day-desc default order', () => {
    renderPage();
    const table = screen.getByRole('table');
    for (const h of ['Name', 'Industry', 'LTP', 'Current Day', '1 Week', '6 Months', '1 Year']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    expect(screen.getAllByText('TRENT').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Consumer Services').length).toBeGreaterThan(0);
    // Default sort = Current Day desc → the gainer (TRENT, +5.06) sorts above the loser (ONGC).
    const desktop = within(table).getAllByText(/TRENT|ONGC/);
    expect(desktop[0]).toHaveTextContent('TRENT');
  });
});

import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { FiiDerivativeRow } from '../../api/types.ts';

const seg = (segment: string, net: string): FiiDerivativeRow => ({
  tradeDate: '2026-06-22',
  segment,
  buyValue: null,
  sellValue: null,
  netValue: net,
});

const data: FiiDerivativeRow[] = [
  seg('INDEX_FUTURES', '598.14'),
  seg('INDEX_OPTIONS', '3340.90'),
  seg('STOCK_FUTURES', '-1425.63'),
  seg('STOCK_OPTIONS', '-233.30'),
];

// EChart pulls ResizeObserver (absent in jsdom); this spec asserts the table only.
vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));
vi.mock('../../api/oiAnalytics.ts', () => ({
  useFiiDerivativeStats: () => ({ data }),
}));

import { FiiDerivativeStatsPage } from './FiiDerivativeStatsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <FiiDerivativeStatsPage />
    </QueryClientProvider>,
  );
}

describe('FiiDerivativeStatsPage', () => {
  it('renders the four F&O segment columns and the pivoted row', () => {
    renderPage();
    const table = screen.getByRole('table');
    for (const h of ['Date', 'Index Futures', 'Index Options', 'Stock Futures', 'Stock Options']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    // The four segment rows pivot into a single dated row.
    expect(within(table).getByText('2026-06-22')).toBeInTheDocument();
  });
});

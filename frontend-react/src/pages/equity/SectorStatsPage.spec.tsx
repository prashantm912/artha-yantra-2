import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SectorStats } from '../../api/types.ts';

const ss: SectorStats = {
  asOf: '2026-06-22',
  sectors: [
    { sector: 'Oil Gas & Consumable Fuels', avgChangePct: '0.50', positive: 3, negative: 1, total: 4 },
  ],
  stocks: [
    { symbol: 'RELIANCE', sector: 'Oil Gas & Consumable Fuels', changePct: '1.30', close: '1326.50', prevClose: '1309.50' },
  ],
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useSectorStats: () => ({ data: ss, isFetching: false, refetch: () => {} }),
}));

import { SectorStatsPage } from './SectorStatsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <SectorStatsPage />
    </QueryClientProvider>,
  );
}

describe('SectorStatsPage', () => {
  it('renders a sector card and the stock factor table', () => {
    renderPage();
    // Sector card: average change pill.
    expect(screen.getByText('+0.50%')).toBeInTheDocument();
    expect(screen.getAllByText('Oil Gas & Consumable Fuels').length).toBeGreaterThan(0);
    // Stock factor table columns + row.
    const table = screen.getByRole('table');
    for (const h of ['Name', 'Chg. %', 'Sector', 'Close', 'Y.Day Close']) {
      expect(within(table).getByRole('columnheader', { name: h })).toBeInTheDocument();
    }
    expect(screen.getAllByText('RELIANCE').length).toBeGreaterThan(0);
  });
});

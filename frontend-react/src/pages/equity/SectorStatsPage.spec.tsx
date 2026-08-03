import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SectorStats } from '../../api/types.ts';

const ss: SectorStats = {
  asOf: '2026-06-22',
  sectorIndices: [
    { name: 'NIFTY METAL', last: '12664.45', changePct: '-3.26', open: '12987.7', high: '12987.7', low: '12647.85', prevClose: '13091.2', asOf: '23-Jun-2026 13:21' },
  ],
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
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 4613ms in a full-suite run.
  it('renders a sector card and the stock factor table', () => {
    renderPage();
    // Live sector-index card.
    expect(screen.getByText('NIFTY METAL')).toBeInTheDocument();
    expect(screen.getByText('-3.26%')).toBeInTheDocument();
    // Sector card: average change pill.
    expect(screen.getByText('+0.50%')).toBeInTheDocument();
    expect(screen.getAllByText('Oil Gas & Consumable Fuels').length).toBeGreaterThan(0);
    // Stock factor table columns + row.
    const table = screen.getByRole('table');
    for (const h of ['Name', 'Chg. %', 'Sector', 'Close', 'Y.Day Close']) {
      expect(within(table).getByRole('columnheader', { name: h })).toBeInTheDocument();
    }
    expect(screen.getAllByText('RELIANCE').length).toBeGreaterThan(0);
  }, 15_000);
});

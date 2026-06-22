import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SectorHeatmap } from '../../api/types.ts';

vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));

const hm: SectorHeatmap = {
  index: 'NIFTY 50',
  asOf: '2026-06-22',
  tiles: [
    { symbol: 'RELIANCE', sector: 'Oil Gas & Consumable Fuels', changePct: '1.30', close: '1326.5', prevClose: '1309.5' },
  ],
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useOiBuzzIndices: () => ({ data: ['NIFTY 50', 'NIFTY BANK'] }),
  useSectorHeatmap: () => ({ data: hm, isFetching: false, refetch: () => {} }),
}));

import { SectorHeatmapPage } from './SectorHeatmapPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <SectorHeatmapPage />
    </QueryClientProvider>,
  );
}

describe('SectorHeatmapPage', () => {
  it('renders the index selector and the as-on date', () => {
    renderPage();
    expect(screen.getByRole('option', { name: 'NIFTY 50' })).toBeInTheDocument();
    expect(screen.getByText(/as on 2026-06-22/)).toBeInTheDocument();
    expect(screen.getByText(/grouped by sector/)).toBeInTheDocument();
  });
});

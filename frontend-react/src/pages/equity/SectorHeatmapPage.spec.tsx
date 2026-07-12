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
  freshness: {
    asOf: '2026-06-22T15:30:00+05:30',
    source: 'bhavcopy',
    historyStart: null,
    staleSeconds: null,
    complete: true,
    provenance: 'eod',
  },
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
  it('renders the index selector and the EOD provenance badge', () => {
    renderPage();
    expect(screen.getByRole('option', { name: 'NIFTY 50' })).toBeInTheDocument();
    // The shared FreshnessBadge renders the KIND word + the as-of date as sibling spans.
    expect(screen.getByText('EOD')).toBeInTheDocument();
    expect(screen.getByText('as of 2026-06-22')).toBeInTheDocument();
    expect(screen.getByText(/grouped by sector/)).toBeInTheDocument();
  });
});

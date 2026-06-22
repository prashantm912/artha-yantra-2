import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { IntervalWiseOi, StrikeMove } from '../../api/types.ts';

const m = (strike: string, oiChange: number, interpretation: StrikeMove['interpretation']): StrikeMove => ({
  strike,
  oiChange,
  interpretation,
});

const data: IntervalWiseOi = {
  gainers15: [m('24100 CE', 300, 'LONG_BUILDUP')],
  losers15: [m('24000 PE', -500, 'LONG_UNWINDING')],
  gainers60: [],
  losers60: [],
  gainersDaily: [],
  losersDaily: [],
  asOf: '2026-06-22T15:24:00+05:30',
};

// Mock the chart (jsdom lacks ResizeObserver) + the filter bar (pulls the symbol store/fetches).
vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));
vi.mock('../../components/FilterBar.tsx', () => ({ FilterBar: () => null }));
vi.mock('../../api/oiAnalytics.ts', () => ({
  useIntervalWiseOi: () => ({ data, isFetching: false, refetch: () => {} }),
}));

import { IntervalWiseOiPage } from './IntervalWiseOiPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <IntervalWiseOiPage />
    </QueryClientProvider>,
  );
}

describe('IntervalWiseOiPage', () => {
  it('renders the six gainer/loser charts, the legend, and an empty state', () => {
    renderPage();
    expect(screen.getByText(/OI Gainer.*15 min/)).toBeInTheDocument();
    expect(screen.getByText(/OI Loser.*Daily/)).toBeInTheDocument();
    expect(screen.getByText('Long Buildup')).toBeInTheDocument();
    // empty timeframes show the no-data note.
    expect(screen.getAllByText(/No data for this window/).length).toBeGreaterThan(0);
  });
});

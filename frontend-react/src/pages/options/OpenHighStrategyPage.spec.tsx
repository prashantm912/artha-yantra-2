import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { OpenHighStrategyStrike } from '../../api/types.ts';

const items: OpenHighStrategyStrike[] = [
  {
    strike: '22500.00',
    ce: {
      optionType: 'CE',
      latestDate: '2026-06-18',
      latestOpen: '125.0000',
      latestHigh: '125.0000',
      latestLow: '100.0000',
      latestClose: '110.0000',
      latestOi: 1200,
      ohMark: true,
      olMark: false,
      triggered: true,
      fallPctFromHigh: '12.00',
      sessions: 3,
      hits: 1,
      probability: '50.00',
    },
    pe: {
      optionType: 'PE',
      latestDate: '2026-06-18',
      latestOpen: '90.0000',
      latestHigh: '130.0000',
      latestLow: '90.0000',
      latestClose: '130.0000',
      latestOi: 1450,
      ohMark: false,
      olMark: true,
      triggered: true,
      fallPctFromHigh: '0.00',
      sessions: 1,
      hits: 0,
      probability: null,
    },
  },
];

// Mock the FilterBar (pulls the symbol store/fetches) + the api hook.
vi.mock('../../components/FilterBar.tsx', () => ({ FilterBar: () => null }));
const useOpenHighStrategy = vi.fn();
vi.mock('../../api/oiAnalytics.ts', () => ({ useOpenHighStrategy: () => useOpenHighStrategy() }));

import { OpenHighStrategyPage } from './OpenHighStrategyPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <OpenHighStrategyPage />
    </QueryClientProvider>,
  );
}

describe('OpenHighStrategyPage', () => {
  it('renders the CE O=H Hit badge and the PE O=L Hit badge for the strike', () => {
    useOpenHighStrategy.mockReturnValue({ data: items, isFetching: false, isLoading: false, refetch: () => {} });
    renderPage();
    // the Call leg triggered Open=High, the Put leg triggered Open=Low (DataTable renders a desktop
    // table + a mobile card view, so each cue appears more than once — assert >= 1).
    expect(screen.getAllByText('O=H Hit').length).toBeGreaterThan(0);
    expect(screen.getAllByText('O=L Hit').length).toBeGreaterThan(0);
    // the historical probability is shown (CE has 3 sessions → 50%).
    expect(screen.getAllByText('50.00%').length).toBeGreaterThan(0);
    // the strike is the centre column.
    expect(screen.getAllByText('22500.00').length).toBeGreaterThan(0);
  });

  it('renders the empty state when no Open=High/Low history accrued', () => {
    useOpenHighStrategy.mockReturnValue({ data: [], isFetching: false, isLoading: false, refetch: () => {} });
    renderPage();
    expect(screen.getByText(/No Open=High \/ Open=Low history/)).toBeInTheDocument();
  });
});

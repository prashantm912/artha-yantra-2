import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { OiExpiryStrike } from '../../api/types.ts';

const items: OiExpiryStrike[] = [
  {
    strike: '22500.00',
    ce: [
      {
        date: '2026-06-18',
        open: '130.0000',
        high: '130.0000',
        low: '125.0000',
        close: '125.0000',
        oi: 1200,
        volume: 7000,
        changeInClosePct: '13.64',
        changeInOiPct: '20.00',
        interpretation: 'LONG_BUILDUP',
        allDayHigh: true,
        allDayLow: false,
      },
      {
        date: '2026-06-17',
        open: '100.0000',
        high: '120.0000',
        low: '100.0000',
        close: '110.0000',
        oi: 1000,
        volume: 5000,
        changeInClosePct: null,
        changeInOiPct: null,
        interpretation: null,
        allDayHigh: false,
        allDayLow: true,
      },
    ],
    pe: [
      {
        date: '2026-06-18',
        open: '90.0000',
        high: '95.0000',
        low: '85.0000',
        close: '90.0000',
        oi: 1500,
        volume: 3000,
        changeInClosePct: null,
        changeInOiPct: null,
        interpretation: null,
        allDayHigh: true,
        allDayLow: true,
      },
    ],
  },
];

// Mock the FilterBar (pulls the symbol store/fetches) + the api hook.
vi.mock('../../components/FilterBar.tsx', () => ({ FilterBar: () => null }));
const useOiExpiry = vi.fn();
vi.mock('../../api/oiAnalytics.ts', () => ({ useOiExpiry: () => useOiExpiry() }));

import { OiExpiryStrategyPage } from './OiExpiryStrategyPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <OiExpiryStrategyPage />
    </QueryClientProvider>,
  );
}

describe('OiExpiryStrategyPage', () => {
  it('renders the CE + PE leg tables for the selected strike', () => {
    useOiExpiry.mockReturnValue({ data: items, isFetching: false, isLoading: false, refetch: () => {} });
    renderPage();
    expect(screen.getByRole('heading', { name: '22500.00 CE' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '22500.00 PE' })).toBeInTheDocument();
    // the day-over-day interpretation badge rides the newest CE row.
    expect(screen.getAllByLabelText('Long Build Up').length).toBeGreaterThan(0);
    // the strike selector is present.
    expect(screen.getByLabelText('Strike')).toBeInTheDocument();
  });

  it('renders the empty state when no EOD history accrued', () => {
    useOiExpiry.mockReturnValue({ data: [], isFetching: false, isLoading: false, refetch: () => {} });
    renderPage();
    expect(screen.getByText(/No EOD OI history/)).toBeInTheDocument();
  });
});

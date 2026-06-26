import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { WorldIndex } from '../../api/types.ts';

// The page now renders the world map (EChart) above the table; EChart pulls ResizeObserver (absent in
// jsdom) — stub it, same as every other chart-page spec. This test asserts the table, not the canvas.
vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));

// A gainer, a loser, and a price-less row (live quote unresolved) so the sign-aware rendering and the
// muted-dash fallback are both exercised deterministically.
const data: WorldIndex[] = [
  {
    name: 'HANG SENG',
    country: 'Hong Kong',
    key: 'GLOBAL_INDEX|^HSI',
    tradingSymbol: '^HSI',
    ltp: '23900.00',
    prevClose: '23661.00',
    netChange: '239.00',
    changePct: '1.01',
    open: '23700.00',
    high: '23950.00',
    low: '23680.00',
    asOf: '2026-06-24T15:30:00+05:30',
    latency: '900 Seconds',
  },
  {
    name: 'SGX NIFTY',
    country: 'India',
    key: 'GLOBAL_INDEX|SGX NIFTY',
    tradingSymbol: 'SGX NIFTY',
    ltp: '23845.85',
    prevClose: '23895.85',
    netChange: '-50.00',
    changePct: '-0.21',
    open: '23900.00',
    high: '23920.00',
    low: '23800.00',
    asOf: '2026-06-24T15:30:00+05:30',
    latency: '900 Seconds',
  },
  {
    name: 'BRENT CRUDE',
    country: 'United Kingdom',
    key: 'GLOBAL_INDICATOR|BZUSD',
    tradingSymbol: 'BZUSD',
    ltp: null,
    prevClose: null,
    netChange: null,
    changePct: null,
    open: null,
    high: null,
    low: null,
    asOf: '2026-06-24T15:30:00+05:30',
    latency: '900 Seconds',
  },
];

vi.mock('../../api/worldIndices.ts', () => ({
  useWorldIndices: () => ({ data, isPending: false, isError: false, isSuccess: true, refetch: () => {} }),
}));

import { WorldIndicesPage } from './WorldIndicesPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <WorldIndicesPage />
    </QueryClientProvider>,
  );
}

describe('WorldIndicesPage', () => {
  it('renders the faithful columns and a sign-aware change per row', () => {
    renderPage();
    const table = screen.getByRole('table');
    for (const h of ['Index', 'Country', 'LTP', 'Net Chg', '% Chg', 'As Of']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    // A gainer carries an explicit +sign; a loser its -sign — text cue, never colour-only.
    expect(within(table).getByText('+239.00')).toBeInTheDocument();
    expect(within(table).getByText('-50.00')).toBeInTheDocument();
    expect(within(table).getByText('+1.01%')).toBeInTheDocument();
    expect(within(table).getByText('-0.21%')).toBeInTheDocument();
    // The price-less row still lists (name shown) with muted dashes for its missing prices.
    expect(within(table).getByText('BRENT CRUDE')).toBeInTheDocument();
  });
});

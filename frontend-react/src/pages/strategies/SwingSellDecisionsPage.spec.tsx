import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SwingSellReport } from '../../api/swing.ts';

const report: SwingSellReport = {
  asOf: '2026-07-09T20:15:00+05:30',
  items: [
    {
      symbol: 'TCS',
      setup: 'minervini-vcp',
      stage: 2,
      footprint: '3T 12w',
      entryPrice: '3800',
      currentPrice: '4180',
      unrealizedPct: '10.00',
      stopLevel: '3496',
      trailLevel: '3990',
      stillBuyable: true,
      sellingNow: false,
      sellReason: null,
      verdict: 'HOLD',
    },
    {
      symbol: 'INFY',
      setup: 'minervini-cheat',
      stage: 2,
      footprint: null,
      entryPrice: '1600',
      currentPrice: '1472',
      unrealizedPct: '-8.00',
      stopLevel: '1472',
      trailLevel: null,
      stillBuyable: false,
      sellingNow: true,
      sellReason: 'STOP_LOSS',
      verdict: 'SELL (STOP_LOSS)',
    },
  ],
};

vi.mock('../../api/swing.ts', async (importActual) => {
  const actual = await importActual<typeof import('../../api/swing.ts')>();
  return {
    ...actual,
    useSwingSellDecisions: () => ({
      data: report,
      isPending: false,
      isError: false,
      isSuccess: true,
      refetch: () => {},
    }),
  };
});

import { SwingSellDecisionsPage } from './SwingSellDecisionsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <SwingSellDecisionsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SwingSellDecisionsPage', () => {
  it('renders the sell-decision table with a HOLD row and a SELL row', () => {
    renderPage();
    const table = screen.getByRole('table');
    for (const h of ['Symbol', 'Setup', 'Unrealized', 'Stop', 'Verdict']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    expect(within(table).getByText('TCS')).toBeInTheDocument();
    expect(within(table).getByText('INFY')).toBeInTheDocument();
    // the exit doctrine fired on INFY → the verdict badge carries the reason
    expect(within(table).getByText('SELL (STOP_LOSS)')).toBeInTheDocument();
    expect(within(table).getByText('HOLD')).toBeInTheDocument();
    // signed unrealized P&L, both directions
    expect(within(table).getByText('+10.00%')).toBeInTheDocument();
    expect(within(table).getByText('-8.00%')).toBeInTheDocument();
  });

  it('summarises the holding + selling counts and offers a book toggle', () => {
    renderPage();
    expect(screen.getByText(/2 holding · 1 selling/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Minervini' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Manas Arora' })).toBeInTheDocument();
  });
});

import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));
vi.mock('../../api/backtests.ts', () => ({
  useCompareResults: () => [
    { id: 'aaaa1111-x', data: { metrics: { sharpe: '1.8', totalReturn: '10' }, equityCurve: [{ ts: '2026-06-01', value: '100' }], dataHash: 'h1' } },
    { id: 'bbbb2222-y', data: { metrics: { sharpe: '1.2', totalReturn: '15' }, equityCurve: [{ ts: '2026-06-01', value: '100' }], dataHash: 'h1' } },
  ],
}));

import { BacktestComparePage } from './BacktestComparePage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/backtests/compare?ids=aaaa1111-x,bbbb2222-y']}>
        <BacktestComparePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BacktestComparePage', () => {
  it('renders the metric matrix with the best value per row highlighted', () => {
    renderPage();
    const matrix = screen.getByRole('table', { name: 'Metric comparison matrix' });
    expect(within(matrix).getByText('Sharpe')).toBeInTheDocument();
    expect(within(matrix).getByText('1.80')).toBeInTheDocument(); // run A sharpe (best)
    expect(within(matrix).getByText('15.00%')).toBeInTheDocument(); // run B total return (best)
    // same dataHash → no mismatch banner
    expect(screen.queryByText(/differing dataHash/)).not.toBeInTheDocument();
  });

  it('ranks the runs in a leaderboard by the chosen edge metric (default total return)', () => {
    renderPage();
    const board = screen.getByRole('table', { name: 'Backtest run leaderboard' });
    const rows = within(board).getAllByRole('row').slice(1); // drop the header row
    // Run B (15% total return) outranks run A (10%) → rank 1 badge on B.
    expect(within(rows[0]).getByText('bbbb2222')).toBeInTheDocument();
    expect(within(rows[0]).getByLabelText('Rank 1')).toBeInTheDocument();
    expect(within(rows[1]).getByText('aaaa1111')).toBeInTheDocument();
  });
});

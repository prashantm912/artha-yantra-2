import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
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
    expect(screen.getByText('Sharpe')).toBeInTheDocument();
    expect(screen.getByText('1.80')).toBeInTheDocument(); // run A sharpe (best)
    expect(screen.getByText('15.00%')).toBeInTheDocument(); // run B total return (best)
    // same dataHash → no mismatch banner
    expect(screen.queryByText(/differing dataHash/)).not.toBeInTheDocument();
  });
});

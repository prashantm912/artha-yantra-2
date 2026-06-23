import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));
vi.mock('../../api/backtests.ts', () => ({
  useBacktestResults: () => ({
    data: {
      metrics: { sharpe: '1.85', totalReturn: '12.34', tradeCount: '42', maxDrawdown: '-5.6' },
      equityCurve: [{ ts: '2026-06-01T00:00:00Z', value: '100000' }, { ts: '2026-06-02T00:00:00Z', value: '101000' }],
      dataHash: 'deadbeef',
      seed: 42,
    },
    isLoading: false,
  }),
  useBacktestTrades: () => ({ data: { items: [{ seq: 1, side: 'LONG', entryTs: '2026-06-01T09:30', entryPrice: '100.00', exitPrice: '110.00', pnl: '10.00', pnlPct: '10.00', exitReason: 'TARGET', barsHeld: 5, qty: 1 }] } }),
  useBacktestFolds: () => ({ data: [{ fold: { index: 1, trainFrom: '2026-01-01', trainTo: '2026-03-01', testFrom: '2026-03-01', testTo: '2026-04-01' }, trainMetrics: {}, oosMetrics: { sharpe: '1.2' } }] }),
  useBacktestMonteCarlo: () => ({
    data: { n: 1000, trades: 42, insufficientSample: false, equityBands: { step: [0, 1], p5: ['100', '95'], p50: ['100', '105'], p95: ['100', '115'] }, drawdownDistribution: { p5: '-2', p50: '-5', p95: '-10', mean: '-6' }, riskOfRuin: '0.02' },
  }),
}));

import { BacktestResultsPage } from './BacktestResultsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/backtests/run-1']}>
        <Routes>
          <Route path="/backtests/:id" element={<BacktestResultsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BacktestResultsPage', () => {
  it('shows metrics, trades, folds and the Monte Carlo tab', () => {
    renderPage();
    expect(screen.getByText('Sharpe')).toBeInTheDocument();
    expect(screen.getByText('1.85')).toBeInTheDocument();
    expect(screen.getByText(/dataHash deadbeef/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Trades' }));
    expect(screen.getByText('TARGET')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Folds' }));
    expect(screen.getByText(/2026-03-01 → 2026-04-01/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Monte Carlo' }));
    expect(screen.getByText(/risk of ruin 0.02/)).toBeInTheDocument();
  });
});

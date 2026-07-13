import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';

vi.mock('../../api/strategies.ts', () => ({
  useStrategies: () => ({ data: { items: [{ id: 's1', name: 'EMA Cross' }] } }),
}));

vi.mock('../../api/optimizations.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/optimizations.ts')>();
  return {
    ...actual,
    useSweeps: () => ({
      data: {
        items: [
          {
            jobId: 'aaaa1111-bb', status: 'running', progress: 40,
            createdAt: '2026-07-14T10:05:00Z', strategyId: 's1', method: 'tpe',
            objective: { metric: 'sharpe', direction: 'maximize' }, error: null,
          },
          {
            jobId: 'cccc2222-dd', status: 'failed', progress: 20,
            createdAt: '2026-07-14T10:00:00Z', strategyId: 'missing', method: 'grid',
            objective: { metric: 'totalReturn', direction: 'maximize' },
            error: 'Optimizer restarted before this sweep completed.',
          },
        ],
        limit: 25,
        offset: 0,
      },
      isLoading: false,
      isFetching: false,
      refetch: vi.fn(),
    }),
  };
});

import { SweepsPage } from './SweepsPage.tsx';

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/backtests/sweeps']}>
        <Routes>
          <Route path="/backtests/sweeps" element={<SweepsPage />} />
          <Route path="/optimizations/:sweepId" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SweepsPage', () => {
  it('lists native sweep summaries, progress, and failure reasons', () => {
    renderPage();
    expect(screen.getAllByText('EMA Cross').length).toBeGreaterThan(0);
    expect(screen.getAllByText('deleted / missing').length).toBeGreaterThan(0);
    expect(screen.getAllByText('40%').length).toBeGreaterThan(0);
    expect(screen.getAllByText('tpe').length).toBeGreaterThan(0);
    expect(screen.getAllByText('sharpe / maximize').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Optimizer restarted before this sweep completed.').length).toBeGreaterThan(0);
  });

  it('links a sweep to the existing detail page', () => {
    renderPage();
    fireEvent.click(screen.getAllByRole('link', { name: 'Open sweep aaaa1111' })[0]);
    expect(screen.getByTestId('location')).toHaveTextContent('/optimizations/aaaa1111-bb');
  });
});

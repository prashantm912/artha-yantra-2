import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const promote = vi.fn();

vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));
vi.mock('../../api/optimizations.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/optimizations.ts')>();
  return {
    ...actual, // keep paramStr
    useSweepBest: () => ({
      data: { metric: 'sharpe', items: [{ trialNumber: 5, params: { period: 9 }, objective: 1.85, plateauObjective: 1.7, backtestRunId: 'run-x' }] },
    }),
    useSweepTrials: () => ({
      data: {
        items: [
          { trialNumber: 5, params: { period: 9 }, objectiveValues: { sharpe: 1.85 }, state: 'COMPLETE' },
          { trialNumber: 6, params: { period: 21 }, state: 'PRUNED' },
        ],
      },
    }),
    usePromoteTrial: () => ({ mutate: promote, isPending: false }),
  };
});

import { SweepDetailPage } from './SweepDetailPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/optimizations/sweep-1']}>
        <Routes>
          <Route path="/optimizations/:sweepId" element={<SweepDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SweepDetailPage', () => {
  it('shows the leaderboard + flagged trials, and promotes a trial to a draft', () => {
    renderPage();
    expect(screen.getAllByText('#5').length).toBeGreaterThan(0); // leaderboard + all-trials
    expect(screen.getByText('COMPLETE')).toBeInTheDocument();
    expect(screen.getByText('PRUNED')).toBeInTheDocument(); // flagged, not hidden

    fireEvent.click(screen.getByText('Promote'));
    expect(screen.getByText('Promote trial → new draft')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Create draft'));
    expect(promote).toHaveBeenCalledWith(5, expect.anything());
  });
});

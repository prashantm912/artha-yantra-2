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
      data: {
        metric: 'sharpe',
        items: [
          {
            trialNumber: 5,
            params: { period: 9 },
            objective: 1.85,
            plateauObjective: 1.7,
            backtestRunId: 'run-x',
            guardMetrics: {
              dataHash: 'h1',
              foldsExcluded: 2,
              regimesCovered: ['BULL', 'BEAR'],
              regimeOosMin: 0.4,
              regimeOosMean: 0.7,
              regimeOosMax: 1.0,
            },
          },
          // a full-window trial — no guards → "no fold guards" badge
          { trialNumber: 8, params: { period: 14 }, objective: 1.2, plateauObjective: 1.1, backtestRunId: 'run-y' },
        ],
      },
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

    fireEvent.click(screen.getAllByText('Promote')[0]);
    expect(screen.getByText('Promote trial → new draft')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Create draft'));
    expect(promote).toHaveBeenCalledWith(5, expect.anything());
  });

  it('surfaces the fold guards (regimes / min-OOS / folds-excluded) and the no-guards badge', () => {
    renderPage();
    // trial 5 carries fold guards
    expect(screen.getByText('BULL')).toBeInTheDocument();
    expect(screen.getByText('BEAR')).toBeInTheDocument();
    expect(screen.getByText('OOS≥ 0.400')).toBeInTheDocument(); // min per-regime OOS Sharpe
    expect(screen.getByText('−2 folds')).toBeInTheDocument(); // min_trades exclusions
    // trial 8 is full-window → the badge, not a hollow guard cell
    expect(screen.getByText('no fold guards')).toBeInTheDocument();
  });
});

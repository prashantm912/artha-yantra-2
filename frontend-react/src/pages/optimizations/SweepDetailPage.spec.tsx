import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
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
              regimesCovered: ['UP_QUIET', 'DOWN_QUIET'],
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
    // The leaderboard rides the shared DataTable (desktop <table> + an md:hidden card list) — scope
    // the leaderboard assertions to the named desktop table so they guard the desktop render, not the
    // card (the jsdom double-paint). COMPLETE/PRUNED live in the raw all-trials table (single render).
    const board = within(screen.getByRole('table', { name: 'Sweep leaderboard' }));
    expect(board.getByText('#5')).toBeInTheDocument();
    expect(screen.getByText('COMPLETE')).toBeInTheDocument();
    expect(screen.getByText('PRUNED')).toBeInTheDocument(); // flagged, not hidden

    fireEvent.click(board.getAllByText('Promote')[0]);
    expect(screen.getByText('Promote trial → new draft')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Create draft'));
    expect(promote).toHaveBeenCalledWith(5, expect.anything());
  });

  it('surfaces the fold guards (regimes / min-OOS / folds-excluded) and the no-guards badge', () => {
    renderPage();
    // The leaderboard rides the shared DataTable, which paints a desktop <table> AND an md:hidden card
    // list — so every guard chip exists twice in jsdom. Scope to the named desktop table.
    const board = within(screen.getByRole('table', { name: 'Sweep leaderboard' }));
    // trial 5 carries fold guards
    expect(board.getByText('UP_QUIET')).toBeInTheDocument();
    expect(board.getByText('DOWN_QUIET')).toBeInTheDocument();
    expect(board.getByText('OOS≥ 0.400')).toBeInTheDocument(); // min per-regime OOS Sharpe
    expect(board.getByText('−2 folds')).toBeInTheDocument(); // min_trades exclusions
    // trial 8 is full-window → the badge, not a hollow guard cell
    expect(board.getByText('no fold guards')).toBeInTheDocument();
  });
});

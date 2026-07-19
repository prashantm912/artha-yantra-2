import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Candidate, Generation } from '../../api/evolution.ts';

const MOCK = {
  sweepJobId: 'sw1',
  metric: 'sharpe',
  direction: 'maximize',
  importance: [{ param: 'ema', score: 0.5, direction: 'positive' }],
  brittleness: [
    { param: 'atrMult', importance: 0.42, localVariance: 0.0123, brittle: true },
    { param: 'rsGate', importance: 0.11, localVariance: null, brittle: false },
  ],
  slices: [
    {
      paramX: 'ema',
      paramY: 'atrMult',
      cells: [
        { x: 10, y: 2, objective: 1.23, count: 4 },
        { x: 20, y: 3, objective: null, count: 1 },
      ],
    },
  ],
  notes: ['note one'],
};

vi.mock('../../api/evolution.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/evolution.ts')>();
  return {
    ...actual,
    useSweepInsights: () => ({
      isPending: false,
      isError: false,
      isSuccess: true,
      data: MOCK,
      refetch: () => {},
    }),
  };
});

import { InsightsView } from './InsightsView.tsx';

const generations = [{ id: 'g1', n: 1, campaignId: 'c', stressTouches: 0 }] as Generation[];
const candidates = [{ id: 'c1', generationId: 'g1', sweepJobId: 'sw1' }] as Candidate[];

function renderView() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <InsightsView generations={generations} candidates={candidates} />
    </QueryClientProvider>,
  );
}

// The brittleness grid now renders through the shared DataTable, which paints a desktop <table> AND a
// md:hidden card list — so each cell text exists twice in jsdom. Scope assertions to the named table.
const grid = () => within(screen.getByRole('table', { name: 'Brittleness grid' }));

describe('InsightsView brittleness grid', () => {
  it('renders the columns in their declared order', () => {
    renderView();
    expect(grid().getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Parameter',
      'Importance',
      'Local variance',
      'Verdict',
    ]);
  });

  it('renders each brittleness row cell-for-cell (— for a null variance)', () => {
    renderView();
    const rows = grid().getAllByRole('row').slice(1); // [0] = header row
    expect(within(rows[0]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'atrMult',
      '0.42',
      '0.0123',
      'brittle',
    ]);
    expect(within(rows[1]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'rsGate',
      '0.11',
      '—',
      'stable',
    ]);
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const apiFetch = vi.fn();
vi.mock('../../api/client.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/client.ts')>();
  return { ...actual, apiFetch: (path: string, opts?: unknown) => apiFetch(path, opts) };
});

import { ApiError } from '../../api/client.ts';
import { InsightsComparePage } from './InsightsComparePage.tsx';

const COMPARE = {
  session: '2026-07-11',
  booksDiffer: false,
  differsMost: 'context',
  columns: [
    {
      signalId: 1,
      tradingsymbol: 'NIFTY25200CE',
      side: 'BUY',
      family: 'scalper',
      priority: 82,
      band: 'A',
      components: [
        { key: 'edge', points: 27, c: 0.9 },
        { key: 'context', points: 15, c: 0.75 },
      ],
      optionLegCost: 12000,
      marginEstimate: 'unpriced',
      riskReward: 2.0,
      entryPrice: 100,
      stopLoss: 80,
      target: 140,
      dataTrust: 'OK',
      trustReasons: [],
      scored: 'SCORED',
    },
    {
      signalId: 2,
      tradingsymbol: 'SENSEX80000PE',
      side: 'SELL',
      family: 'scalper',
      priority: 74,
      band: 'B',
      components: [
        { key: 'edge', points: 24, c: 0.8 },
        { key: 'context', points: 5, c: 0.5 },
      ],
      optionLegCost: 15000,
      marginEstimate: 'unpriced',
      riskReward: 1.5,
      entryPrice: 200,
      stopLoss: 230,
      target: 120,
      dataTrust: 'OK',
      trustReasons: [],
      scored: 'SCORED',
    },
  ],
  notes: ["Margin is 'unpriced' here — the POST /market/margin estimate is the FE column."],
};

beforeEach(() => {
  apiFetch.mockReset();
  apiFetch.mockImplementation((path: string) => {
    if (path.startsWith('/insights/compare')) return Promise.resolve(COMPARE);
    return Promise.resolve({});
  });
});

function renderAt(entry: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[entry]}>
        <InsightsComparePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('InsightsComparePage', () => {
  it('shows the select-2–6 instruction when no ids are present', () => {
    renderAt('/insights/compare');
    expect(screen.getByText(/Select 2–6 signals to compare/)).toBeInTheDocument();
    expect(apiFetch).not.toHaveBeenCalled();
  });

  it('sends 2–6 ids comma-joined to the compare endpoint', async () => {
    renderAt('/insights/compare?signalIds=1,2');
    await screen.findByRole('link', { name: 'NIFTY25200CE' });
    expect(apiFetch.mock.calls.some(([p]) => typeof p === 'string' && p === '/insights/compare?signalIds=1,2')).toBe(true);
  });

  it('renders the matrix with both signals, the what-differs banner and honest unpriced margin', async () => {
    renderAt('/insights/compare?signalIds=1,2');
    expect(await screen.findByRole('link', { name: 'NIFTY25200CE' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'SENSEX80000PE' })).toBeInTheDocument();
    // what-differs banner + the differs-most component marker.
    expect(screen.getByText(/differ mainly on/)).toBeInTheDocument();
    expect(screen.getAllByText('differs most').length).toBeGreaterThan(0);
    // priority row.
    expect(screen.getByText('82')).toBeInTheDocument();
    expect(screen.getByText('74')).toBeInTheDocument();
    // margin is honest "unpriced", never 0 — one cell per column.
    expect(screen.getAllByText('unpriced').length).toBe(2);
  });

  it('surfaces the 422 comparability reason (different sessions) rather than a generic error', async () => {
    apiFetch.mockImplementation(() =>
      Promise.reject(new ApiError(422, 'VALIDATION_FAILED', 'cannot compare signals from different sessions')),
    );
    renderAt('/insights/compare?signalIds=1,2');
    expect(await screen.findByText('cannot compare signals from different sessions')).toBeInTheDocument();
  });
});

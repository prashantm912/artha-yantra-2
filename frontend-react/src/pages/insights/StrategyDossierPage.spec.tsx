import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const apiFetch = vi.fn();
vi.mock('../../api/client.ts', () => ({
  apiFetch: (path: string, opts?: unknown) => apiFetch(path, opts),
}));

import { StrategyDossierPage } from './StrategyDossierPage.tsx';

const DOSSIER = {
  strategyId: 'abc-123',
  slug: 'siva-vwap',
  name: 'Siva VWAP',
  enabled: true,
  stage: 'TAKE_ELIGIBLE',
  criteria: [
    { name: 'trades', required: '≥ 20', actual: '34', pass: true },
    { name: 'profitFactor', required: '≥ 1.3', actual: '1.1', pass: false },
  ],
  graduatedAt: null,
  crossingTimeline: [{ at: '2026-07-10T21:10:00+05:30', severity: 'NOTICE', title: 'PF crossed above 1.3' }],
  rejectionProfile: [
    { rail: 'volume_floor', count: 12 },
    { rail: 'rsi', count: 5 },
  ],
  openSellDecisions: [
    { sellDecisionId: 7, runDate: '2026-07-09', symbol: 'INFY', verdict: 'SELL', unrealizedPct: -3.2, acknowledged: false },
  ],
  asOf: '2026-07-11T09:00:00+05:30',
  notes: ['Backtest refs (/backtests/summary) and the scalper shadow-variant slice are FE deep-links.'],
};

beforeEach(() => {
  apiFetch.mockReset();
  apiFetch.mockImplementation((path: string) => {
    if (path.startsWith('/insights/strategy-dossier/')) return Promise.resolve(DOSSIER);
    return Promise.resolve({});
  });
});

function renderDossier() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/insights/strategy-dossier/abc-123']}>
        <Routes>
          <Route path="/insights/strategy-dossier/:strategyId" element={<StrategyDossierPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('StrategyDossierPage', () => {
  it('fetches the dossier for the routed strategy id', async () => {
    renderDossier();
    await screen.findByText('Siva VWAP');
    expect(apiFetch.mock.calls.some(([p]) => typeof p === 'string' && p === '/insights/strategy-dossier/abc-123')).toBe(true);
  });

  it('renders the criteria, crossing timeline, rejection profile and open sell-decisions', async () => {
    renderDossier();
    expect(await screen.findByText('Siva VWAP')).toBeInTheDocument();
    expect(screen.getByText('TAKE_ELIGIBLE')).toBeInTheDocument();
    // criteria pass-map.
    expect(screen.getByText('trades')).toBeInTheDocument();
    expect(screen.getByText('profitFactor')).toBeInTheDocument();
    // crossing timeline.
    expect(screen.getByText('PF crossed above 1.3')).toBeInTheDocument();
    // rejection profile.
    expect(screen.getByText('volume_floor')).toBeInTheDocument();
    // open sell-decisions.
    expect(screen.getByText('INFY')).toBeInTheDocument();
  });
});

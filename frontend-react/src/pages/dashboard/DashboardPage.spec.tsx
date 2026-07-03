import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../api/dashboard.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/dashboard.ts')>();
  return {
    ...actual, // keep JOB_ACTIVE_STATES
    useSystemStatus: () => ({
      data: {
        overall: 'UP',
        services: [],
        kite: { session: 'VALID', ticker: 'CONNECTED', lastTickAgeMs: 120, rateBudget: null },
        market: { phase: 'OPEN' },
        jobs: { running: 1, queued: 2 },
        asOf: '2026-06-23T13:30:00+05:30',
      },
    }),
    useRecentJobs: () => ({
      data: { items: [{ jobId: 'abcdef12-3456', kind: 'backtest', status: 'running', progress: 42 }] },
    }),
  };
});
vi.mock('../../api/signals.ts', () => ({
  useSignals: () => ({
    data: {
      items: [
        {
          id: 1,
          exchange: 'NSE',
          tradingsymbol: 'RELIANCE',
          signalType: 'ENTRY',
          side: 'BUY',
          status: 'ACTIVE',
          generatedAt: '2026-06-23T09:30:00+05:30',
          compositeScore: '0.7',
        },
      ],
    },
  }),
}));
vi.mock('../../api/paper.ts', () => ({
  usePaperAccount: () => ({ data: { dayPnl: '2500.00' } }),
  usePaperPnl: () => ({ data: { summary: { realizedTotal: '12500.00', trades: 4, winRate: '0.75' } } }),
  usePaperPositions: () => ({ data: { items: [{ id: 1 }] } }),
}));
vi.mock('../../api/health.ts', () => ({
  useDataHealth: () => ({
    data: {
      status: 'GREEN',
      marketOpen: true,
      asOf: '2026-06-23T13:30:00+05:30',
      tickedTokens: 42,
      problems: [],
    },
  }),
}));

import { DashboardPage } from './DashboardPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DashboardPage', () => {
  it('renders the status strip, active signals, paper P&L and in-flight jobs', () => {
    renderPage();
    expect(screen.getByText('OPEN')).toBeInTheDocument(); // market phase
    expect(screen.getByText('OK · 42 live')).toBeInTheDocument(); // data-health canary tile
    expect(screen.getByText('1 running · 2 queued')).toBeInTheDocument();
    expect(screen.getByText('RELIANCE')).toBeInTheDocument(); // active signal
    expect(screen.getByText('12500.00')).toBeInTheDocument(); // realized P&L
    expect(screen.getByText('backtest')).toBeInTheDocument(); // job tile
  });
});

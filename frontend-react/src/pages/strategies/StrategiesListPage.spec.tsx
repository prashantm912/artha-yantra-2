import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../api/strategies.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/strategies.ts')>();
  return {
    ...actual, // keep sparkPoints + STRATEGY_STATUSES + NOTIFY_CHANNELS
    useStrategies: () => ({
      data: {
        items: [
          {
            id: 's1',
            slug: 'ema-cross',
            name: 'EMA Cross',
            currentVersion: '1.2.0',
            publishedVersion: '1.1.0',
            currentVersionId: 'v-cur',
            publishedVersionId: 'v-pub',
            status: 'published',
            tags: ['intraday'],
            updatedAt: '2026-06-22T10:00:00+05:30',
            notificationsEnabled: false,
            notificationChannel: null,
          },
        ],
      },
      isLoading: false,
    }),
    useBacktestSummaries: () => ({
      data: { 'v-pub': { strategyVersionId: 'v-pub', runId: 'r1', sharpe: '1.85', totalReturn: '0.12', maxDrawdown: '-0.05', equity: ['100', '110', '105', '120'] } },
    }),
    useSetNotifications: () => ({ mutate: vi.fn() }),
  };
});

import { StrategiesListPage } from './StrategiesListPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <StrategiesListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('StrategiesListPage', () => {
  it('lists strategies with status, version and last-backtest Sharpe', () => {
    renderPage();
    expect(screen.getByText('EMA Cross')).toBeInTheDocument();
    expect(screen.getAllByText('published').length).toBeGreaterThan(0); // badge + status-filter option
    expect(screen.getByText('1.1.0')).toBeInTheDocument(); // published version
    expect(screen.getByText(/Sharpe 1.85/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'History' })).toHaveAttribute('href', '/strategies/s1/versions');
  });
});

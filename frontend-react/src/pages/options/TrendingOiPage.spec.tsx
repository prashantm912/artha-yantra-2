import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { TrendSeries } from '../../api/types.ts';

const data: TrendSeries = {
  asOf: '2026-06-16T09:21:00+05:30',
  items: [
    { bucket: '2026-06-16T09:18:00+05:30', totalOi: 2000, ceOi: 1000, peOi: 1000, spot: '57100', trend: 'FLAT', ceLtp: null, peLtp: null },
    { bucket: '2026-06-16T09:21:00+05:30', totalOi: 2400, ceOi: 1100, peOi: 1300, spot: '57200', trend: 'UP', ceLtp: null, peLtp: null },
  ],
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useTrendingOi: () => ({ data, isFetching: false, isLoading: false, refetch: vi.fn() }),
  useChainTable: () => ({ data: null, isFetching: false, isLoading: false }),
}));
// jsdom has no canvas — swap the EChart atom for its accessible shell.
vi.mock('../../components/atoms/EChart.tsx', () => ({
  EChart: ({ ariaLabel }: { ariaLabel: string }) => <div role="img" aria-label={ariaLabel} />,
}));

import userEvent from '@testing-library/user-event';
import { TrendingOiPage } from './TrendingOiPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <TrendingOiPage />
    </QueryClientProvider>,
  );
}

describe('TrendingOiPage', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 6853ms in a full-suite run.
  it('renders the faithful column headers and a Bullish sentiment row', () => {
    renderPage();
    const table = screen.getByRole('table');
    for (const h of ['Diff. in OI', 'Net PCR', 'Sentiment', 'Day H/L Break']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    // The newest bucket (ΔPut 300 > ΔCall 100 → Diff +200) reads Bullish.
    expect(within(table).getAllByText('Bullish').length).toBeGreaterThan(0);
  }, 15_000);

  it('Graph view swaps the table for the ΔOI chart', async () => {
    renderPage();
    await userEvent.click(screen.getByLabelText('Graph view'));
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(
      screen.getByRole('img', { name: /Cumulative change in call and put OI/ }),
    ).toBeInTheDocument();
  });
});

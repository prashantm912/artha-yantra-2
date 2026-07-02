import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { IndexContribution } from '../../api/types.ts';

const data: IndexContribution = {
  index: 'NIFTY 50',
  indexChangePct: '0.45',
  advanceTotal: '0.60',
  declineTotal: '-0.15',
  indexLevel: '24117.65',
  advancePoints: '39.89',
  declinePoints: '-10.66',
  advances: [
    { rank: 1, symbol: 'RELIANCE', contribution: '0.1654', changePct: '2.00', close: '1020.00', points: '39.89' },
  ],
  declines: [
    { rank: 1, symbol: 'HINDALCO', contribution: '-0.0451', changePct: '-2.96', close: '983.90', points: '-10.66' },
  ],
  asOf: '2026-06-22',
  live: true,
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useOiBuzzIndices: () => ({ data: ['NIFTY 50', 'NIFTY BANK'] }),
  useIndexContribution: () => ({ data, isFetching: false, refetch: () => {} }),
}));

import { IndexContributionPage } from './IndexContributionPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <IndexContributionPage />
    </QueryClientProvider>,
  );
}

describe('IndexContributionPage', () => {
  it('renders the advances/declines split with contributors', () => {
    renderPage();
    expect(screen.getByText(/Advances: 1/)).toBeInTheDocument();
    expect(screen.getByText(/Declines: 1/)).toBeInTheDocument();
    expect(screen.getAllByText('RELIANCE').length).toBeGreaterThan(0);
    expect(screen.getAllByText('HINDALCO').length).toBeGreaterThan(0);
    expect(screen.getByRole('option', { name: 'NIFTY 50' })).toBeInTheDocument();
    // the live fold shows the Live dot (not the EOD badge) + the live index level in the header;
    // "Live" appears twice: the toggle label and the LiveDot text.
    expect(screen.getAllByText('Live').length).toBeGreaterThan(1);
    expect(screen.queryByText(/EOD · as of/)).not.toBeInTheDocument();
    expect(screen.getAllByText(/24117\.65/).length).toBeGreaterThan(0);
    expect(screen.getByLabelText('Live')).toBeChecked();
  });
});

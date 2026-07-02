import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Mock the FilterBar (pulls the symbol store/fetches) + the chain hook feeding the option picker.
vi.mock('../../components/FilterBar.tsx', () => ({ FilterBar: () => null }));
const useChainTable = vi.fn();
vi.mock('../../api/oiAnalytics.ts', () => ({ useChainTable: () => useChainTable() }));

import { RiskCalculatorPage } from './RiskCalculatorPage.tsx';

const leg = (tradingsymbol: string, ltp: string | null) => ({
  leg: {
    tradingsymbol,
    ltp,
    bid: null,
    ask: null,
    volume: null,
    oi: null,
    iv: null,
    delta: null,
    gamma: null,
    theta: null,
    vega: null,
    rho: null,
    ivReason: 'OK',
    priceSource: null,
  },
  deltas: null,
});

const chain = {
  underlying: 'NIFTY 50',
  expiry: '2026-07-07',
  spot: '24168.45',
  forward: null,
  forwardSource: null,
  riskFreeRate: null,
  pcr: null,
  stale: false,
  asOf: '2026-07-02T10:00:00Z',
  interval: '3m',
  rows: [{ strike: '24150.00', ce: leg('NIFTY26707 24150 CE', '138.05'), pe: leg('NIFTY26707 24150 PE', '96.10') }],
};

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <RiskCalculatorPage />
    </QueryClientProvider>,
  );
}

describe('RiskCalculatorPage option picker', () => {
  it('fills the entry with the picked leg LTP', async () => {
    useChainTable.mockReturnValue({ data: chain, isFetching: false, isLoading: false });
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /Use LTP/ }));
    expect(screen.getByLabelText('Entry price')).toHaveValue(138.05);
  });

  it('disables Use LTP when the picked leg has no traded price', () => {
    useChainTable.mockReturnValue({
      data: { ...chain, rows: [{ strike: '24150.00', ce: leg('X', null), pe: null }] },
      isFetching: false,
      isLoading: false,
    });
    renderPage();
    expect(screen.getByRole('button', { name: /Use LTP/ })).toBeDisabled();
  });
});

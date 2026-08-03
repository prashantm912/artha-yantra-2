import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ChainTable } from '../../api/types.ts';

const chain: ChainTable = {
  underlying: 'NIFTY 50',
  expiry: '2026-06-23',
  spot: '100',
  forward: null,
  forwardSource: null,
  riskFreeRate: null,
  pcr: null,
  stale: false,
  lastCaptured: false,
  asOf: '2026-06-22T15:24:00+05:30',
  interval: '5m',
  rows: [
    {
      strike: '100',
      ce: { leg: makeLeg('100CE', '5', '0.5'), deltas: null },
      pe: { leg: makeLeg('100PE', '4', '-0.5'), deltas: null },
    },
  ],
};

function makeLeg(sym: string, ltp: string, delta: string) {
  return {
    exchange: 'NFO',
    tradingsymbol: sym,
    ltp,
    bid: null,
    ask: null,
    volume: null,
    oi: null,
    iv: null,
    delta,
    gamma: '0',
    theta: '0',
    vega: '0',
    rho: '0',
    ivReason: 'OK',
    priceSource: 'LTP',
  };
}

vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));
vi.mock('../../components/FilterBar.tsx', () => ({ FilterBar: () => null }));
vi.mock('../../stores/symbolContext.store.ts', () => ({
  useSymbolContext: (sel: (s: { name: string }) => unknown) => sel({ name: 'NIFTY 50' }),
}));
vi.mock('../../api/oiAnalytics.ts', () => ({
  useChainTable: () => ({ data: chain, isFetching: false, refetch: () => {} }),
}));

import { StrategyBuilderPage } from './StrategyBuilderPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <StrategyBuilderPage />
    </QueryClientProvider>,
  );
}

describe('StrategyBuilderPage', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3051ms in a full-suite run.
  it('adds a leg from the chain and shows the payoff summary', () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('Strike'), { target: { value: '100' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add leg' }));

    // the legs table shows the added leg (desktop + mobile cell), and the payoff summary appears
    // (Buy CE 100, premium 5, NIFTY lot 75 → net debit 5 × 75 = ₹375).
    expect(screen.getAllByText('100 CE').length).toBeGreaterThan(0);
    expect(screen.getByText('Net Premium')).toBeInTheDocument();
    expect(screen.getByText(/Dr ₹375/)).toBeInTheDocument();
  }, 15_000);
});

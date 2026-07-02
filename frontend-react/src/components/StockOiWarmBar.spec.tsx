import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useSymbolContext } from '../stores/symbolContext.store.ts';
import type { StockChainWarmStatus } from '../api/stockChainWarm.ts';

const statusData: { current: StockChainWarmStatus | undefined } = { current: undefined };
const start = vi.fn();

vi.mock('../api/stockChainWarm.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/stockChainWarm.ts')>();
  return {
    ...actual,
    useStockChainWarmStatus: () => ({ data: statusData.current }),
    useStartStockChainWarm: () => ({ mutate: start, isPending: false }),
  };
});

import { StockOiWarmBar } from './StockOiWarmBar.tsx';

function renderBar() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <StockOiWarmBar />
    </QueryClientProvider>,
  );
}

const idle: StockChainWarmStatus = {
  underlying: 'RELIANCE',
  expiry: '2026-07-28',
  day: '2026-07-02',
  state: 'IDLE',
  totalLegs: 0,
  fetchedLegs: 0,
  rowsInStore: 0,
};

describe('StockOiWarmBar', () => {
  beforeEach(() => {
    statusData.current = idle;
    start.mockClear();
  });

  it('renders nothing for a captured index', () => {
    useSymbolContext.setState({ name: 'NIFTY 50' });
    const { container } = renderBar();
    expect(container).toBeEmptyDOMElement();
  });

  it('offers Load OI data for a cold stock and shows progress while running', () => {
    useSymbolContext.setState({ name: 'RELIANCE' });
    renderBar();
    expect(screen.getByRole('button', { name: 'Load OI data' })).toBeInTheDocument();

    statusData.current = { ...idle, state: 'RUNNING', totalLegs: 132, fetchedLegs: 40 };
    renderBar();
    expect(screen.getByText('Loading… 40/132 legs')).toBeInTheDocument();
  });

  it('explains the unavailable state instead of offering the button', () => {
    useSymbolContext.setState({ name: 'RELIANCE' });
    statusData.current = { ...idle, state: 'UNAVAILABLE' };
    renderBar();
    expect(screen.getByText(/not configured on this stack/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

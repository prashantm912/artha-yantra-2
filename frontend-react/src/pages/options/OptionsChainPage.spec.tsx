import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Audit 2026-07-02 §3-P0 regression: while the chain query is pending (first mount, or gated on the
// expiry cascade), the page must show a SKELETON — never the final "No chain for this selection" copy.
const state = {
  chain: {
    data: undefined as unknown,
    isPending: true,
    isLoading: true,
    isError: false,
    isSuccess: false,
    isFetching: true,
    refetch: vi.fn(),
  },
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useChainTable: () => state.chain,
  useVix: () => ({ data: undefined, isPending: true, refetch: vi.fn() }),
}));

import { OptionsChainPage } from './OptionsChainPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <OptionsChainPage />
    </QueryClientProvider>,
  );
}

describe('OptionsChainPage', () => {
  it('renders a skeleton while the chain is pending — never the empty-state copy', () => {
    renderPage();
    expect(screen.getByTestId('qs-loading')).toBeInTheDocument();
    expect(screen.queryByText(/No chain for this selection/)).not.toBeInTheDocument();
  });

  it('renders the empty copy only on a SETTLED empty result', () => {
    state.chain = {
      ...state.chain,
      data: null,
      isPending: false,
      isLoading: false,
      isSuccess: true,
      isFetching: false,
    };
    renderPage();
    expect(screen.getByText(/No chain for this selection/)).toBeInTheDocument();
    expect(screen.queryByTestId('qs-loading')).not.toBeInTheDocument();
  });
});

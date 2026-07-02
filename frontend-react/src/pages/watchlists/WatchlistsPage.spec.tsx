import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const remove = vi.fn();

vi.mock('../../api/watchlists.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/watchlists.ts')>();
  return {
    ...actual, // keep SCREENER_PRESETS
    useWatchlists: () => ({ data: { items: [{ id: 'w1', name: 'Scalps', items: [{ exchange: 'NSE', tradingsymbol: 'RELIANCE' }] }] } }),
    useCreateWatchlist: () => ({ mutate: vi.fn() }),
    useAddWatchItem: () => ({ mutate: vi.fn() }),
    useRemoveWatchItem: () => ({ mutate: remove }),
    useScreener: () => ({ data: { items: [] } }),
    useInstrumentSearch: () => ({ data: [] }),
  };
});

import { WatchlistsPage } from './WatchlistsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <WatchlistsPage />
    </QueryClientProvider>,
  );
}

describe('WatchlistsPage', () => {
  it('shows the selected list items, removes one, and switches to the screener tab', () => {
    renderPage();
    expect(screen.getByText('NSE:RELIANCE')).toBeInTheDocument();
    // Remove now confirms first (§10.2-2).
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    fireEvent.click(screen.getByText('Remove'));
    expect(remove).toHaveBeenCalledWith({ id: 'w1', item: { exchange: 'NSE', tradingsymbol: 'RELIANCE' } });
    confirmSpy.mockRestore();

    fireEvent.click(screen.getByRole('tab', { name: 'Screener' }));
    expect(screen.getByText(/Pick a preset and run the screener/)).toBeInTheDocument();
  });
});

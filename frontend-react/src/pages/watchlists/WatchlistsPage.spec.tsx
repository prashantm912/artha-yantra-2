import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
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
    // Both tables render through the shared DataTable, which paints a desktop <table> AND a
    // md:hidden mobile card list — every cell exists twice in jsdom (CSS doesn't apply), so
    // scope to the desktop table to keep these assertions exactly-one.
    const table = screen.getByRole('table');
    expect(within(table).getByText('NSE:RELIANCE')).toBeInTheDocument();
    // Remove now confirms first (§10.2-2).
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    fireEvent.click(within(table).getByText('Remove'));
    expect(remove).toHaveBeenCalledWith({ id: 'w1', item: { exchange: 'NSE', tradingsymbol: 'RELIANCE' } });
    confirmSpy.mockRestore();

    fireEvent.click(screen.getByRole('tab', { name: 'Screener' }));
    expect(within(screen.getByRole('table')).getByText(/Pick a preset and run the screener/)).toBeInTheDocument();
  });

  it('keeps the actions column header accessible but visually hidden', () => {
    renderPage();
    const actions = within(screen.getByRole('table')).getByRole('columnheader', { name: 'Actions' });
    expect(actions).toHaveClass('ay-sr-only');
  });

  it('renders the screener columns in their declared order', () => {
    renderPage();
    fireEvent.click(screen.getByRole('tab', { name: 'Screener' }));
    expect(
      within(screen.getByRole('table'))
        .getAllByRole('columnheader')
        .map((h) => h.textContent),
    ).toEqual(['Instrument', 'Close', 'Value', 'Label']);
  });
});

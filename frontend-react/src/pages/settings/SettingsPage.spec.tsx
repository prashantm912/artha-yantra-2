import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const syncMutate = vi.fn();

vi.mock('../../api/settings.ts', () => ({
  useKiteStatus: () => ({ data: { connected: true, state: 'CONNECTED', kiteUserId: 'AB1234', tickerState: 'RUNNING' }, refetch: vi.fn() }),
  useSyncStatus: () => ({ data: { state: 'OK', lastRun: '2026-06-23T08:00:00' }, refetch: vi.fn() }),
  useSyncInstruments: () => ({ mutate: syncMutate, isPending: false }),
  fetchKiteLoginUrl: vi.fn(),
}));

import { SettingsPage } from './SettingsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <SettingsPage />
    </QueryClientProvider>,
  );
}

describe('SettingsPage', () => {
  it('shows Kite status, the theme select and triggers a data sync', () => {
    renderPage();
    expect(screen.getByText('Kite Connect')).toBeInTheDocument();
    expect(screen.getByText('AB1234')).toBeInTheDocument();
    expect(screen.getByLabelText('Theme')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Sync instruments' }));
    expect(syncMutate).toHaveBeenCalled();
  });
});

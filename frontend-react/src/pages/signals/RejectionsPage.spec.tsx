import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// A success-but-empty query (QueryState renders the empty card, not a crash) for the main table.
const emptyList = { isPending: false, isError: false, isSuccess: true, data: { items: [] }, refetch: () => {} };

vi.mock('../../api/signalRejections.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/signalRejections.ts')>();
  return {
    ...actual, // keep the shared types
    useSignalRejections: () => emptyList,
    useRejectionRailCounts: () => ({ data: { items: [] } }),
    useShadowSummary: () => ({ data: { items: [] } }),
    useDotHealth: () => ({
      isLoading: false,
      isError: false,
      data: {
        asOf: '2026-07-10T10:00:00+05:30',
        session: true,
        rowsInspected: 12,
        dots: [
          { dot: 'breadth', alive: true, required: true, detail: 'input live in the last 12 rejections' },
          { dot: 'iv_rank', alive: false, required: true, detail: 'input dead across 12 rejections' },
          { dot: 'dow', alive: false, required: false, detail: 'input dead across 12 rejections' },
        ],
      },
    }),
  };
});

import { RejectionsPage } from './RejectionsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <RejectionsPage />
    </QueryClientProvider>,
  );
}

describe('RejectionsPage dot-health panel', () => {
  it('renders per-dot liveness with honest words + required markers', () => {
    renderPage();

    // Each dot renders its name and its verdict WORD (colour is never the only cue).
    expect(screen.getByText('breadth')).toBeInTheDocument();
    expect(screen.getAllByText('Live').length).toBeGreaterThan(0); // breadth alive
    expect(screen.getAllByText('Dead').length).toBeGreaterThan(0); // iv_rank + dow dead

    // The as-of / session / sample-size line.
    expect(screen.getByText(/12 rejections inspected/)).toBeInTheDocument();

    // Required dots carry a marker chip.
    expect(screen.getAllByText('required').length).toBeGreaterThan(0);
  });
});

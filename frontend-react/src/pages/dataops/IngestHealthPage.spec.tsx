import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { BoardReport } from '../../api/ingestHealth.ts';

const board: BoardReport = {
  generatedAt: '2026-09-16T03:15:00Z',
  fromDay: '2026-09-11',
  toDay: '2026-09-15',
  tradingDays: 3,
  sources: [
    {
      source: 'NSE_FII_DII',
      policy: 'REQUIRE_SUCCESS',
      status: 'GREEN',
      detail: '1 SUCCESS run(s), 30 rows',
      missingDays: 0,
      days: [
        { day: '2026-09-15', status: 'GREEN' },
        { day: '2026-09-14', status: 'GREEN' },
        { day: '2026-09-11', status: 'GREEN' },
      ],
      lastRun: {
        status: 'SUCCESS',
        rowsWritten: 30,
        startedAt: '2026-09-15T13:30:00Z',
        finishedAt: '2026-09-15T13:31:00Z',
        error: null,
        stale: false,
      },
    },
    {
      source: 'MINERVINI_SCREEN',
      policy: 'SCREENER',
      status: 'YELLOW',
      detail: 'SUCCESS but rows_written=0 — data-starved screen skip',
      missingDays: 1,
      days: [
        { day: '2026-09-15', status: 'YELLOW' },
        { day: '2026-09-14', status: 'RED' },
        { day: '2026-09-11', status: 'GREEN' },
      ],
      lastRun: {
        status: 'SUCCESS',
        rowsWritten: 0,
        startedAt: '2026-09-15T13:30:00Z',
        finishedAt: '2026-09-15T13:30:30Z',
        error: null,
        stale: false,
      },
    },
    {
      source: 'BHAVCOPY',
      policy: 'REQUIRE_SUCCESS',
      status: 'RED',
      detail: 'a run is stuck RUNNING (> 120m, never finished) — crashed mid-flight',
      missingDays: 2,
      days: [
        { day: '2026-09-15', status: 'RED' },
        { day: '2026-09-14', status: 'RED' },
        { day: '2026-09-11', status: 'GREEN' },
      ],
      lastRun: {
        status: 'RUNNING',
        rowsWritten: null,
        startedAt: '2026-09-14T13:30:00Z',
        finishedAt: null,
        error: null,
        stale: true,
      },
    },
  ],
};

vi.mock('../../api/ingestHealth.ts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/ingestHealth.ts')>()),
  useIngestHealth: () => ({
    data: board,
    isPending: false,
    isError: false,
    isSuccess: true,
    refetch: () => {},
  }),
}));

import { IngestHealthPage } from './IngestHealthPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <IngestHealthPage />
    </QueryClientProvider>,
  );
}

describe('IngestHealthPage', () => {
  it('renders the per-source health table with verdicts, missing-days and last-run', () => {
    renderPage();

    // Title + summary strip.
    expect(screen.getByRole('heading', { name: 'Ingest Health' })).toBeInTheDocument();
    expect(screen.getByText(/2026-09-11 → 2026-09-15/)).toBeInTheDocument();
    expect(screen.getByText('1 healthy')).toBeInTheDocument();
    expect(screen.getByText('1 degraded')).toBeInTheDocument();
    expect(screen.getByText('1 failing')).toBeInTheDocument();

    // Table columns.
    const table = screen.getByRole('table');
    for (const h of ['Source', 'Status', 'Missing', 'Last run', 'Rows', 'Detail']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }

    // A source row + its verdict word (colour is never the sole cue).
    expect(screen.getAllByText('NSE_FII_DII').length).toBeGreaterThan(0);
    expect(screen.getAllByText('GREEN').length).toBeGreaterThan(0);
    expect(screen.getAllByText('YELLOW').length).toBeGreaterThan(0);
    expect(screen.getAllByText('RED').length).toBeGreaterThan(0);

    // The stale crashed-run marker surfaces on the last-run cell.
    expect(screen.getAllByText(/stale/).length).toBeGreaterThan(0);
    // The data-starved screener detail is shown.
    expect(screen.getAllByText(/data-starved/).length).toBeGreaterThan(0);
  });
});

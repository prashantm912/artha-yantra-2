import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { BoardReport, ChainReport } from '../../api/ingestHealth.ts';

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

const completeChain: ChainReport = {
  generatedAt: '2026-09-15T14:05:00Z',
  day: '2026-09-15',
  tradingDay: true,
  total: 9,
  done: 9,
  complete: true,
  sources: [
    {
      source: 'NSE_FII_DII',
      state: 'DONE',
      status: 'SUCCESS',
      startedAt: '2026-09-15T13:30:00Z',
      finishedAt: '2026-09-15T13:31:00Z',
    },
  ],
};

const pendingChain: ChainReport = {
  generatedAt: '2026-09-15T14:10:00Z',
  day: '2026-09-15',
  tradingDay: true,
  total: 9,
  done: 7,
  complete: false,
  sources: [
    {
      source: 'EQUITY_BREADTH',
      state: 'PENDING',
      status: null,
      startedAt: null,
      finishedAt: null,
    },
    {
      source: 'MANAS_SCREEN',
      state: 'STUCK',
      status: 'RUNNING',
      startedAt: '2026-09-15T12:30:00Z',
      finishedAt: null,
    },
  ],
};

/**
 * Critical-1 regression: a STUCK source alone (no PENDING) must still read as outstanding, not
 * "complete". An earlier version let STUCK count as resolved server-side, which would have made
 * this exact shape render "safe to shut down" while MANAS_SCREEN was actually orphaned.
 */
const stuckOnlyChain: ChainReport = {
  generatedAt: '2026-09-15T18:59:00Z',
  day: '2026-09-15',
  tradingDay: true,
  total: 9,
  done: 8,
  complete: false,
  sources: [
    {
      source: 'MANAS_SCREEN',
      state: 'STUCK',
      status: 'RUNNING',
      startedAt: '2026-09-15T12:30:00Z',
      finishedAt: null,
    },
  ],
};

/**
 * Major-4 regression: a FAILURE row is terminal, so it is DONE and `complete` stays true — but the
 * evening is NOT clean. The panel used to branch on `complete` alone and announce
 * "complete — safe to shut down" into a role="status" aria-live region on exactly this shape.
 */
const failedChain: ChainReport = {
  generatedAt: '2026-09-15T13:29:00Z',
  day: '2026-09-15',
  tradingDay: true,
  total: 9,
  done: 9,
  complete: true,
  sources: [
    {
      source: 'NSE_FII_DII',
      state: 'DONE',
      status: 'SUCCESS',
      startedAt: '2026-09-15T13:15:00Z',
      finishedAt: '2026-09-15T13:16:00Z',
    },
    {
      source: 'DATA_QUALITY',
      state: 'DONE',
      status: 'FAILURE',
      startedAt: '2026-09-15T13:20:00Z',
      finishedAt: '2026-09-15T13:21:00Z',
    },
  ],
};

let chain: ChainReport = completeChain;

vi.mock('../../api/ingestHealth.ts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/ingestHealth.ts')>()),
  useIngestHealth: () => ({
    data: board,
    isPending: false,
    isError: false,
    isSuccess: true,
    refetch: () => {},
  }),
  useEveningChainStatus: () => ({
    data: chain,
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
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 4052ms in a full-suite run.
  it('renders the per-source health table with verdicts, missing-days and last-run', () => {
    chain = completeChain;
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
  }, 15_000);

  it("shows the evening chain complete and safe-to-shut-down when every source is done", () => {
    chain = completeChain;
    renderPage();

    expect(screen.getByText(/Evening chain complete 9\/9/)).toBeInTheDocument();
    expect(screen.getByText(/safe to shut down/)).toBeInTheDocument();
  }, 15_000);

  it('names still-pending and stuck sources when the chain is not yet complete', () => {
    chain = pendingChain;
    renderPage();

    expect(screen.getByText(/still pending: EQUITY_BREADTH/)).toBeInTheDocument();
    expect(screen.getAllByText('STUCK').length).toBeGreaterThan(0);
  }, 15_000);

  it('never says safe-to-shut-down when the only outstanding source is STUCK (Critical 1)', () => {
    chain = stuckOnlyChain;
    renderPage();

    expect(screen.queryByText(/safe to shut down/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Evening chain complete/)).not.toBeInTheDocument();
    // The "still pending:" line must name the stuck source, not render an empty list.
    expect(screen.getByText(/still pending: MANAS_SCREEN \(stuck\)/)).toBeInTheDocument();
  }, 15_000);

  it('never says safe-to-shut-down when a source FAILED, even though the chain is complete (Major 4)', () => {
    chain = failedChain;
    renderPage();

    // The announcement is a role="status" aria-live region — a screen-reader user gets ONLY this
    // sentence, so it must not be the opposite of the truth.
    expect(screen.queryByText(/safe to shut down/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Evening chain complete/)).not.toBeInTheDocument();
    expect(screen.getByText(/FAILED: DATA_QUALITY/)).toBeInTheDocument();
    expect(screen.getByText(/not clean/)).toBeInTheDocument();
  }, 15_000);
});

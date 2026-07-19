import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';

// LogFeed auto-scrolls to its tail on mount; jsdom has no scrollIntoView.
Element.prototype.scrollIntoView ??= () => {};

const q = <T,>(data: T) => ({ isPending: false, isError: false, isSuccess: true, data, refetch: () => {} });

const EXPIRED = {
  jobId: 'j1',
  state: 'OK',
  startedAt: null,
  lastRun: null,
  durationMs: 0,
  expiries: 0,
  contracts: 0,
  legsWritten: 0,
  legsSkipped: 0,
  legsFailed: 0,
  candleRows: 0,
  currentExpiry: null,
  error: null,
  recentLogs: [],
};
const OI = {
  jobId: 'j2',
  state: 'OK',
  underlying: null,
  expiry: null,
  session: null,
  startedAt: null,
  lastRun: null,
  durationMs: 0,
  optionRows: 0,
  futuresRows: 0,
  error: null,
};
const QUOTA = { configured: false, windows: [] };
const JOBS = [
  {
    id: 1,
    kind: 'EXPIRED',
    params: null,
    status: 'COMPLETED',
    rowsWritten: 12345,
    error: null,
    startedAt: '2026-07-01T10:00:00Z',
    finishedAt: '2026-07-01T10:05:00Z',
  },
  {
    id: 2,
    kind: 'EQUITY_DAILY',
    params: null,
    status: 'FAILED',
    rowsWritten: null,
    error: 'boom',
    startedAt: '2026-07-02T10:00:00Z',
    finishedAt: null,
  },
];

vi.mock('../../api/dataops.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/dataops.ts')>();
  return {
    ...actual,
    useExpiredStatus: () => q(EXPIRED),
    useOiStatus: () => q(OI),
    useQuota: () => q(QUOTA),
    useBackfillJobs: () => q(JOBS),
  };
});

import { StatusPage } from './StatusPage.tsx';

// Replicate the page's local formatters so the expected timestamp/number cells are computed the same
// way the page computes them (locale/TZ-agnostic across this machine and CI).
const fmt = (n: number) => n.toLocaleString();
const ts = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

// The backfill-history table renders through the shared DataTable (desktop <table> + md:hidden card
// list) — scope to the named desktop table so cell text stays exactly-one.
const table = () => within(screen.getByRole('table', { name: 'Backfill history' }));

describe('StatusPage backfill history', () => {
  it('renders the columns in their declared order', () => {
    render(<StatusPage />);
    expect(table().getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Kind',
      'Status',
      'Rows',
      'Started',
      'Finished',
      'Error',
    ]);
  });

  it('renders each run row cell-for-cell (— for a null count / open finish)', () => {
    render(<StatusPage />);
    const rows = table().getAllByRole('row').slice(1); // [0] = header row
    expect(within(rows[0]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'EXPIRED',
      'COMPLETED',
      fmt(12345),
      ts('2026-07-01T10:00:00Z'),
      ts('2026-07-01T10:05:00Z'),
      '',
    ]);
    expect(within(rows[1]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'EQUITY_DAILY',
      'FAILED',
      '—',
      ts('2026-07-02T10:00:00Z'),
      '—',
      'boom',
    ]);
  });
});

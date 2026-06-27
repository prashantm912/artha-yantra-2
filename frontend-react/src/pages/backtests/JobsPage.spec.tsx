import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const cancel = vi.fn();

vi.mock('../../api/strategies.ts', () => ({
  // s1's newest version is 1.0.1 (id v-cur) → a job on 1.0.1 is "latest", 1.0.0 is "old".
  useStrategies: () => ({ data: { items: [{ id: 's1', name: 'EMA Cross', currentVersion: '1.0.1', currentVersionId: 'v-cur', tags: [] }] } }),
}));
const JOBS = [
  { jobId: 'aaaa1111-bb', kind: 'BACKTEST', status: 'completed', progress: 100, createdAt: '2026-06-23T10:00:00', strategyId: 's1', strategyVersion: '1.0.1' },
  { jobId: 'cccc2222-dd', kind: 'OPTIMIZATION', status: 'running', progress: 40, createdAt: '2026-06-23T10:05:00', strategyId: 's1', strategyVersion: '1.0.0' },
];
vi.mock('../../api/backtests.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/backtests.ts')>();
  return {
    ...actual, // keep JOB_STATUSES + fetchResultRef
    // arg 7 = currentVersions (server-side "latest only", "strategyId:version" pairs): model the
    // server filter keeping only jobs on the current version.
    useJobs: (...args: unknown[]) => {
      const currentVersions = args[6];
      const items = currentVersions ? JOBS.filter((j) => j.strategyVersion === '1.0.1') : JOBS;
      return { data: { items }, isFetching: false, isLoading: false, refetch: () => {} };
    },
    useJobsLive: () => {},
    useCancelJob: () => ({ mutate: cancel }),
  };
});

import { JobsPage } from './JobsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <JobsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('JobsPage', () => {
  it('lists jobs with status, version badges, a results link for completed backtests, and cancels a running job', () => {
    renderPage();
    expect(screen.getByText('aaaa1111')).toBeInTheDocument();
    expect(screen.getAllByText('completed').length).toBeGreaterThan(0); // badge + status-filter option
    expect(screen.getByText('Results')).toBeInTheDocument(); // completed backtest
    expect(screen.getByText('Sweep')).toBeInTheDocument(); // optimization job

    // version column: the run on the newest version is flagged latest, the prior version old.
    // (DataTable renders a desktop table + a mobile card view, so each label appears twice.)
    expect(screen.getAllByText('v1.0.1').length).toBeGreaterThan(0);
    expect(screen.getAllByText('latest').length).toBeGreaterThan(0);
    expect(screen.getAllByText('v1.0.0').length).toBeGreaterThan(0);
    expect(screen.getAllByText('old').length).toBeGreaterThan(0);

    // cancel the running optimization (the completed job's cancel is disabled)
    const cancels = screen.getAllByText('Cancel');
    fireEvent.click(cancels[1]);
    expect(cancel).toHaveBeenCalledWith('cccc2222-dd');
  });

  it('"Latest version only" re-queries with the current-version ids (server-side, all pages)', () => {
    renderPage();
    expect(screen.getAllByText('v1.0.0').length).toBeGreaterThan(0); // both versions visible by default

    fireEvent.click(screen.getByLabelText('Latest version only'));
    expect(screen.queryAllByText('v1.0.0')).toHaveLength(0); // old-version job dropped by the server filter
    expect(screen.getAllByText('v1.0.1').length).toBeGreaterThan(0); // latest-version job kept
  });
});

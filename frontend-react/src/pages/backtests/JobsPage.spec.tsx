import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const cancel = vi.fn();

vi.mock('../../api/backtests.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/backtests.ts')>();
  return {
    ...actual, // keep JOB_STATUSES + fetchResultRef
    useJobs: () => ({
      data: {
        items: [
          { jobId: 'aaaa1111-bb', kind: 'BACKTEST', status: 'completed', progress: 100, createdAt: '2026-06-23T10:00:00' },
          { jobId: 'cccc2222-dd', kind: 'OPTIMIZATION', status: 'running', progress: 40, createdAt: '2026-06-23T10:05:00' },
        ],
      },
      isFetching: false,
      isLoading: false,
      refetch: () => {},
    }),
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
  it('lists jobs with status, a results link for completed backtests, and cancels a running job', () => {
    renderPage();
    expect(screen.getByText('aaaa1111')).toBeInTheDocument();
    expect(screen.getAllByText('completed').length).toBeGreaterThan(0); // badge + status-filter option
    expect(screen.getByText('Results')).toBeInTheDocument(); // completed backtest
    expect(screen.getByText('Sweep')).toBeInTheDocument(); // optimization job

    // cancel the running optimization (the completed job's cancel is disabled)
    const cancels = screen.getAllByText('Cancel');
    fireEvent.click(cancels[1]);
    expect(cancel).toHaveBeenCalledWith('cccc2222-dd');
  });
});

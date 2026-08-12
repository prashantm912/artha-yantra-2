import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Radix DropdownMenu (Popper-based) needs these jsdom shims to open in a test.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver;
Element.prototype.scrollIntoView ??= () => {};
Element.prototype.hasPointerCapture ??= () => false;
Element.prototype.setPointerCapture ??= () => {};
Element.prototype.releasePointerCapture ??= () => {};

const cancel = vi.fn();
const annotate = vi.fn();
const createView = vi.fn();
const deleteView = vi.fn();
const rerun = vi.fn();
// Latest args useJobs was called with (index 7 = the D4 per-job tag filter) + the saved-view store the
// Views dropdown reads. Reset per test via renderPage's callers.
let lastJobsArgs: unknown[] = [];
let savedViews: Array<{ id: string; kind: string; name: string; filter: unknown }> = [];

vi.mock('../../api/strategies.ts', () => ({
  // s1's newest version is 1.0.1 (id v-cur) → a job on 1.0.1 is "latest", 1.0.0 is "old".
  useStrategies: () => ({ data: { items: [{ id: 's1', name: 'EMA Cross', currentVersion: '1.0.1', currentVersionId: 'v-cur', tags: [] }] } }),
}));
const JOBS = [
  {
    jobId: 'aaaa1111-bb', kind: 'BACKTEST', status: 'completed', progress: 100,
    createdAt: '2026-06-23T10:00:00', strategyId: 's1', strategyVersion: '1.0.1',
    testFrom: '2026-05-01T00:00:00.000Z', testTo: '2026-06-01T00:00:00.000Z',
    interval: '1d', initialCapital: '100000', seed: 42,
  },
  { jobId: 'cccc2222-dd', kind: 'OPTIMIZATION', status: 'running', progress: 40, createdAt: '2026-06-23T10:05:00', strategyId: 's1', strategyVersion: '1.0.0' },
  // A failed run — the LIST carries no `error`; the failure dialog fetches it via useJobDetail.
  { jobId: 'eeee3333-ff', kind: 'BACKTEST', status: 'failed', progress: 0, createdAt: '2026-06-23T10:10:00', strategyId: 's1', strategyVersion: '1.0.1' },
];
vi.mock('../../api/backtests.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/backtests.ts')>();
  return {
    ...actual, // keep JOB_STATUSES + fetchResultRef
    // arg 6 = currentVersions (server-side "latest only", "strategyId:version" pairs); arg 7 = the
    // NEW per-job tag filter. Model the server filter keeping only jobs on the current version.
    useJobs: (...args: unknown[]) => {
      lastJobsArgs = args;
      const currentVersions = args[6];
      const items = currentVersions ? JOBS.filter((j) => j.strategyVersion === '1.0.1') : JOBS;
      return { data: { items }, isFetching: false, isLoading: false, refetch: () => {} };
    },
    useJobsLive: () => {},
    useCancelJob: () => ({ mutate: cancel }),
    useSubmitRun: () => ({ mutate: rerun, isPending: false }),
    // The detail endpoint is the only source of jobs.error — the dialog reads it from here.
    useJobDetail: () => ({ isPending: false, isError: false, data: { error: 'Kaboom: preflight DATA_GAP at 2026-06-23' } }),
    // D4 P2-2 annotation + saved-view hooks (asserted at the URL/body level in backtests.spec.ts).
    useAnnotateJob: () => ({ mutate: annotate, isPending: false }),
    useSavedViews: () => ({ data: { items: savedViews } }),
    useCreateSavedView: () => ({ mutate: createView, isPending: false }),
    useDeleteSavedView: () => ({ mutate: deleteView, isPending: false }),
  };
});

import { JobsPage } from './JobsPage.tsx';

function LocationProbe() {
  const location = useLocation();
  return <pre data-testid="location">{JSON.stringify({ pathname: location.pathname, state: location.state })}</pre>;
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <Routes>
          <Route path="*" element={<JobsPage />} />
          <Route path="/backtests/run" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('JobsPage', () => {
  beforeEach(() => {
    cancel.mockClear();
    annotate.mockClear();
    createView.mockClear();
    deleteView.mockClear();
    rerun.mockClear();
    savedViews = [];
    lastJobsArgs = [];
  });

  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3249ms in a full-suite run.
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
  }, 15_000);

  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3050ms in a full-suite run.
  it('"Latest version only" re-queries with the current-version ids (server-side, all pages)', () => {
    renderPage();
    expect(screen.getAllByText('v1.0.0').length).toBeGreaterThan(0); // both versions visible by default

    fireEvent.click(screen.getByLabelText('Latest version only'));
    expect(screen.queryAllByText('v1.0.0')).toHaveLength(0); // old-version job dropped by the server filter
    expect(screen.getAllByText('v1.0.1').length).toBeGreaterThan(0); // latest-version job kept
  }, 15_000);

  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3456ms in a full-suite run.
  it('opens an accessible failure dialog showing jobs.error for a failed run', () => {
    renderPage();
    // Row-level indicator: the failed badge is a button (rendered in the desktop table + mobile card).
    const triggers = screen.getAllByLabelText(/Show why run eeee3333 failed/);
    expect(triggers.length).toBeGreaterThan(0);
    fireEvent.click(triggers[0]);

    // The dialog surfaces the BE-served error text (fetched lazily from the detail endpoint).
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(/Kaboom: preflight DATA_GAP/)).toBeInTheDocument();
  }, 15_000);

  // Budget 2026-08-03 (#1061 suite-growth rule): measured 5825ms in a full-suite run.
  it('reruns a backtest with the exact echoed parameters', () => {
    renderPage();
    fireEvent.click(screen.getAllByRole('button', { name: 'Rerun job aaaa1111' })[0]);
    expect(rerun).toHaveBeenCalledWith(
      {
        strategyId: 's1',
        strategyVersion: '1.0.1',
        from: '2026-05-01T00:00:00.000Z',
        to: '2026-06-01T00:00:00.000Z',
        interval: '1d',
        initialCapital: '100000',
        seed: 42,
      },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
  }, 15_000);

  // Budget 2026-08-03 (#1061 suite-growth rule): measured 6052ms in a full-suite run.
  it('clones a backtest into the runner through router state', () => {
    renderPage();
    fireEvent.click(screen.getAllByRole('button', { name: 'Clone job aaaa1111' })[0]);
    expect(screen.getByTestId('location')).toHaveTextContent('"pathname":"/backtests/run"');
    expect(screen.getByTestId('location')).toHaveTextContent('"strategyId":"s1"');
    expect(screen.getByTestId('location')).toHaveTextContent('"initialCapital":"100000"');
    expect(screen.getByTestId('location')).toHaveTextContent('"seed":42');
  }, 15_000);

  // --- D4 P2-2: per-job tags/note + saved views ---

  it('the per-job tag filter feeds useJobs its own `tag` arg (separate from the strategy-tag filter)', () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('Filter by job tag'), { target: { value: 'alpha' } });
    // arg 7 = the NEW per-job tag; the strategy-tag → strategyIds path (arg 5) is left untouched.
    expect(lastJobsArgs[7]).toBe('alpha');
    expect(lastJobsArgs[5]).toBeNull();
  });

  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3231ms in a full-suite run.
  it('edits a run’s tags + note and PATCHes the full annotation set', () => {
    renderPage();
    fireEvent.click(screen.getAllByLabelText(/Edit tags and note for job aaaa1111/)[0]);
    const dialog = screen.getByRole('dialog');
    const tagInput = within(dialog).getByLabelText('Add a tag');
    fireEvent.change(tagInput, { target: { value: 'newtag' } });
    fireEvent.keyDown(tagInput, { key: 'Enter' });
    fireEvent.change(within(dialog).getByLabelText('Note'), { target: { value: 'checked' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(annotate).toHaveBeenCalledWith(
      expect.objectContaining({ jobId: 'aaaa1111-bb', tags: ['newtag'], note: 'checked' }),
      expect.anything(),
    );
  }, 15_000);

  // Budget 2026-08-03 (#1061 suite-growth rule): measured 6486ms in a full-suite run.
  it('saves the CURRENT filter set as a named view', () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Save view' })); // toolbar trigger
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('View name'), { target: { value: 'my view' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save view' })); // dialog submit
    expect(createView).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'backtest_jobs',
        name: 'my view',
        filter: expect.objectContaining({ status: null, strategyId: null, jobTag: null, latestOnly: false }),
      }),
      expect.anything(),
    );
  }, 15_000);

  // Explicit budget (2026-08-01, #1061 suite-growth rule): this heavy renderPage + Radix-popover
  // test sits close to the default 5s and tips over whenever added specs elsewhere reshuffle worker
  // scheduling — it passes 9/9 in isolation. Per house rule this gets a per-test budget rather than
  // a global testTimeout bump.
  it('applies a saved view back onto the page filters', () => {
    savedViews = [
      { id: 'v1', kind: 'backtest_jobs', name: 'Running only', filter: { status: 'running', strategyId: null, jobTag: null, latestOnly: false, sort: null } },
    ];
    renderPage();
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Load a saved view' }), { button: 0 });
    fireEvent.click(screen.getByText('Running only'));
    // Applying restores status='running' → the next render re-queries useJobs with it (arg 0).
    expect(lastJobsArgs[0]).toBe('running');
  }, 15_000);
});

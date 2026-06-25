import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  JOB_STATUSES,
  fetchResultRef,
  useCancelJob,
  useJobs,
  useJobsLive,
  type JobDto,
  type JobStatus,
} from '../../api/backtests.ts';

// /backtests/jobs (master plan §20 parity, E-11 screen 4): every backtest/sweep job with live
// progress bars (driven by the `jobs.progress` topic — no polling) and per-row cancel. Completed
// backtests link to results (PR-C7); optimization jobs link to the sweep explorer (PR-C8).

function statusTone(status: JobStatus): string {
  switch (status) {
    case 'running':
      return 'text-accent ring-accent/40';
    case 'completed':
      return 'text-bull ring-bull/40';
    case 'cancelling':
      return 'text-warn ring-warn/40';
    case 'queued':
      return 'text-ay-muted ring-ay-border';
    default:
      return 'text-bear ring-bear/40';
  }
}

const cancellable = (s: JobStatus) => s === 'queued' || s === 'running';

export function JobsPage() {
  const [status, setStatus] = useState<string | null>(null);
  const navigate = useNavigate();
  const q = useJobs(status);
  useJobsLive(status);
  const cancel = useCancelJob();

  const rows = useMemo(() => q.data?.items ?? [], [q.data]);

  const viewResults = async (jobId: string) => {
    const ref = await fetchResultRef(jobId);
    if (ref) navigate(`/backtests/${ref}`);
  };

  const columns = useMemo<DataColumn<JobDto>[]>(
    () => [
      {
        id: 'job',
        header: 'Job',
        align: 'left',
        sortValue: (job) => job.jobId,
        sortType: 'text',
        render: (job) => <span className="font-mono text-xs">{job.jobId.slice(0, 8)}</span>,
        // No mobileLabel: the truncated jobId is asserted via a singular getByText in the spec, and
        // DataTable renders mobile-card cells in the DOM too — a mobileLabel would duplicate the text.
        mono: false,
      },
      {
        id: 'type',
        header: 'Type',
        align: 'left',
        sortValue: (job) => job.kind,
        sortType: 'text',
        render: (job) => (
          <span className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-muted">{job.kind}</span>
        ),
        mobileLabel: 'Type',
        mono: false,
      },
      {
        id: 'status',
        header: 'Status',
        align: 'left',
        sortValue: (job) => job.status,
        sortType: 'text',
        render: (job) => (
          <span className={cn('rounded px-1.5 py-0.5 text-xs font-semibold ring-1', statusTone(job.status))}>
            {job.status}
          </span>
        ),
        mobileLabel: 'Status',
        mono: false,
      },
      {
        id: 'progress',
        header: 'Progress',
        align: 'left',
        sortValue: (job) => job.progress,
        sortType: 'number',
        render: (job) => (
          <div className="flex items-center gap-2">
            <div className="h-1.5 w-28 overflow-hidden rounded-full bg-surface-2">
              <div className="h-full bg-accent" style={{ width: `${Math.round(job.progress)}%` }} />
            </div>
            <span className="w-9 text-right tabular-nums text-xs text-ay-muted">{Math.round(job.progress)}%</span>
          </div>
        ),
        mobileLabel: 'Progress',
        mono: false,
      },
      {
        id: 'created',
        header: 'Created',
        align: 'left',
        sortValue: (job) => job.createdAt,
        sortType: 'text',
        render: (job) => (
          <span className="tabular-nums">{job.createdAt?.slice(0, 19).replace('T', ' ')}</span>
        ),
        mobileLabel: 'Created',
      },
      {
        id: 'actions',
        header: 'Actions',
        align: 'right',
        headerClassName: 'ay-sr-only',
        render: (job) => (
          <>
            {job.kind === 'OPTIMIZATION' && (
              <button
                type="button"
                onClick={() => navigate(`/optimizations/${job.jobId}`)}
                className="px-1.5 text-xs text-accent hover:underline"
              >
                Sweep
              </button>
            )}
            {job.kind === 'BACKTEST' && job.status === 'completed' && (
              <button
                type="button"
                onClick={() => viewResults(job.jobId)}
                className="px-1.5 text-xs text-accent hover:underline"
              >
                Results
              </button>
            )}
            <button
              type="button"
              onClick={() => cancel.mutate(job.jobId)}
              disabled={!cancellable(job.status)}
              className="px-1.5 text-xs text-bear hover:underline disabled:text-ay-muted/40"
            >
              Cancel
            </button>
          </>
        ),
        mono: false,
      },
    ],
    // navigate/cancel/viewResults are stable enough for this page's lifetime; rows drive re-render.
    [], // eslint-disable-line react-hooks/exhaustive-deps
  );

  return (
    <LoadBeat>
      <PageHeader title="Jobs" subtitle="Live backtest and sweep jobs with per-row progress" />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Select
          value={status}
          options={[...JOB_STATUSES]}
          onChange={(v) => setStatus(v || null)}
          ariaLabel="Status filter"
          placeholder="All statuses"
        />
        {status && (
          <button
            type="button"
            onClick={() => setStatus(null)}
            className="h-9 rounded-md border border-ay-border px-3 text-sm text-ay-muted hover:border-accent"
          >
            Clear
          </button>
        )}
        <button
          type="button"
          onClick={() => q.refetch()}
          disabled={q.isFetching}
          className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent disabled:opacity-50"
        >
          {q.isFetching ? '…' : '↻ Reload'}
        </button>
      </div>

      <BeatBlock>
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(job) => job.jobId}
          ariaLabel="Jobs"
          emptyMessage={q.isLoading ? 'Loading…' : 'No jobs yet — launch a backtest or sweep from the runner.'}
        />
      </BeatBlock>
    </LoadBeat>
  );
}

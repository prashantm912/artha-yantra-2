import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { useStrategies } from '../../api/strategies.ts';
import {
  JOB_STATUSES,
  JOBS_PAGE_SIZE,
  fetchResultRef,
  useCancelJob,
  useJobs,
  useJobsLive,
  type JobDto,
  type JobStatus,
} from '../../api/backtests.ts';

// /backtests/jobs (master plan §20 parity, E-11 screen 4): every backtest/sweep job with live
// progress bars (driven by the `jobs.progress` topic — no polling) and per-row cancel. Filterable by
// status + strategy, paginated; rows carry the strategy name + the completed run's return. Completed
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
  const [strategyId, setStrategyId] = useState<string | null>(null);
  const [offset, setOffset] = useState(0);
  const navigate = useNavigate();
  const q = useJobs(status, strategyId, offset);
  useJobsLive(status, strategyId, offset);
  const cancel = useCancelJob();
  const strategies = useStrategies('', null);

  const rows = useMemo(() => q.data?.items ?? [], [q.data]);
  const stratOptions = useMemo(
    () => (strategies.data?.items ?? []).map((s) => ({ value: s.id, label: s.name })),
    [strategies.data],
  );
  const nameById = useMemo(
    () => new Map((strategies.data?.items ?? []).map((s) => [s.id, s.name])),
    [strategies.data],
  );

  // Reset to the first page whenever a filter changes (a stale offset can land past the result set).
  useEffect(() => setOffset(0), [status, strategyId]);

  const viewResults = async (jobId: string) => {
    const ref = await fetchResultRef(jobId);
    if (ref) navigate(`/backtests/${ref}`);
  };

  const strategyName = (job: JobDto) =>
    (job.strategyId ? nameById.get(job.strategyId) : null) ??
    (job.strategyId ? job.strategyId.slice(0, 8) : '—');

  const columns = useMemo<DataColumn<JobDto>[]>(
    () => [
      {
        id: 'job',
        header: 'Job',
        align: 'left',
        sortValue: (job) => job.jobId,
        sortType: 'text',
        render: (job) => <span className="font-mono text-xs">{job.jobId.slice(0, 8)}</span>,
        mono: false,
      },
      {
        id: 'strategy',
        header: 'Strategy',
        align: 'left',
        sortValue: (job) => strategyName(job),
        sortType: 'text',
        render: (job) => <span className="text-sm">{strategyName(job)}</span>,
        mobileLabel: 'Strategy',
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
        id: 'return',
        header: 'Return',
        align: 'right',
        sortValue: (job) => (job.totalReturn == null ? Number.NEGATIVE_INFINITY : Number(job.totalReturn)),
        sortType: 'number',
        render: (job) =>
          job.totalReturn == null ? (
            <span className="text-ay-muted">—</span>
          ) : (
            <span className={cn('tabular-nums', isNegative(job.totalReturn) ? 'text-bear' : 'text-bull')}>
              {formatDecimal(job.totalReturn, 2)}%
            </span>
          ),
        mobileLabel: 'Return',
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
    // nameById drives the strategy column's label; navigate/cancel/viewResults are stable.
    [nameById], // eslint-disable-line react-hooks/exhaustive-deps
  );

  const page = Math.floor(offset / JOBS_PAGE_SIZE) + 1;
  const hasNext = rows.length === JOBS_PAGE_SIZE;

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
        <Select
          value={strategyId}
          options={stratOptions}
          onChange={(v) => setStrategyId(v || null)}
          ariaLabel="Strategy filter"
          placeholder="All strategies"
          className="max-w-[16rem]"
        />
        {(status || strategyId) && (
          <button
            type="button"
            onClick={() => {
              setStatus(null);
              setStrategyId(null);
            }}
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

      <div className="mt-3 flex items-center justify-end gap-2 text-sm">
        <span className="text-ay-muted">Page {page}</span>
        <button
          type="button"
          onClick={() => setOffset((o) => Math.max(0, o - JOBS_PAGE_SIZE))}
          disabled={offset === 0 || q.isFetching}
          className="h-9 rounded-md border border-ay-border px-3 hover:border-accent disabled:opacity-40"
        >
          ‹ Prev
        </button>
        <button
          type="button"
          onClick={() => setOffset((o) => o + JOBS_PAGE_SIZE)}
          disabled={!hasNext || q.isFetching}
          className="h-9 rounded-md border border-ay-border px-3 hover:border-accent disabled:opacity-40"
        >
          Next ›
        </button>
      </div>
    </LoadBeat>
  );
}

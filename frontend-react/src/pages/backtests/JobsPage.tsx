import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  JOB_STATUSES,
  fetchResultRef,
  useCancelJob,
  useJobs,
  useJobsLive,
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

      <BeatBlock className="max-h-[calc(100vh-12rem)] overflow-auto rounded-lg border border-ay-border">
        <table className="w-full border-collapse text-sm">
          <thead className="sticky top-0 bg-surface-1 text-left text-xs uppercase text-ay-muted">
            <tr>
              <th className="px-2 py-2 font-medium">Job</th>
              <th className="px-2 py-2 font-medium">Type</th>
              <th className="px-2 py-2 font-medium">Status</th>
              <th className="px-2 py-2 font-medium">Progress</th>
              <th className="px-2 py-2 font-medium">Created</th>
              <th className="px-2 py-2"><span className="ay-sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((job) => (
              <tr key={job.jobId} className="border-t border-ay-border">
                <td className="px-2 py-2 font-mono text-xs">{job.jobId.slice(0, 8)}</td>
                <td className="px-2 py-2">
                  <span className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-muted">{job.kind}</span>
                </td>
                <td className="px-2 py-2">
                  <span className={cn('rounded px-1.5 py-0.5 text-xs font-semibold ring-1', statusTone(job.status))}>
                    {job.status}
                  </span>
                </td>
                <td className="px-2 py-2">
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-28 overflow-hidden rounded-full bg-surface-2">
                      <div className="h-full bg-accent" style={{ width: `${Math.round(job.progress)}%` }} />
                    </div>
                    <span className="w-9 text-right tabular-nums text-xs text-ay-muted">{Math.round(job.progress)}%</span>
                  </div>
                </td>
                <td className="px-2 py-2 tabular-nums">{job.createdAt?.slice(0, 19).replace('T', ' ')}</td>
                <td className="px-2 py-2 text-right">
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
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-2 py-6 text-center text-ay-muted">
                  {q.isLoading ? 'Loading…' : 'No jobs yet — launch a backtest or sweep from the runner.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </BeatBlock>
    </LoadBeat>
  );
}

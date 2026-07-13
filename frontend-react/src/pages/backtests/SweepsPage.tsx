import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { useStrategies } from '../../api/strategies.ts';
import {
  SWEEPS_PAGE_SIZE,
  useSweeps,
  type SweepObjective,
  type SweepSummary,
} from '../../api/optimizations.ts';

function statusTone(status: string): string {
  switch (status) {
    case 'running':
      return 'text-accent ring-accent/40';
    case 'completed':
      return 'text-bull ring-bull/40';
    case 'queued':
      return 'text-ay-muted ring-ay-border';
    default:
      return 'text-bear ring-bear/40';
  }
}

function objectiveLabel(objective?: SweepObjective | null): string {
  const primary = objective?.objectives?.[0] ?? objective;
  if (!primary?.metric) return '-';
  return `${primary.metric} / ${primary.direction ?? 'maximize'}`;
}

export function SweepsPage() {
  const [offset, setOffset] = useState(0);
  const sweeps = useSweeps(offset);
  const strategies = useStrategies('', null);
  const rows = sweeps.data?.items ?? [];
  const nameById = useMemo(
    () => new Map((strategies.data?.items ?? []).map((strategy) => [strategy.id, strategy.name])),
    [strategies.data],
  );

  const strategyName = (sweep: SweepSummary) =>
    (sweep.strategyId ? nameById.get(sweep.strategyId) : null) ??
    (sweep.strategyId ? `deleted / ${sweep.strategyId.slice(0, 8)}` : '-');

  const columns = useMemo<DataColumn<SweepSummary>[]>(
    () => [
      {
        id: 'sweep',
        header: 'Sweep',
        align: 'left',
        help: 'Short id of this optimizer sweep; open it for trials, leaderboard, folds, and promotion.',
        render: (sweep) => (
          <Link
            to={`/optimizations/${sweep.jobId}`}
            aria-label={`Open sweep ${sweep.jobId.slice(0, 8)}`}
            className="font-mono text-xs text-accent hover:underline"
          >
            {sweep.jobId.slice(0, 8)}
          </Link>
        ),
        mobileLabel: 'Sweep',
        mono: false,
      },
      {
        id: 'strategy',
        header: 'Strategy',
        align: 'left',
        help: 'The strategy whose parameter space this sweep explores.',
        render: (sweep) => <span className="text-sm">{strategyName(sweep)}</span>,
        mobileLabel: 'Strategy',
        mono: false,
      },
      {
        id: 'method',
        header: 'Method',
        align: 'left',
        help: 'The optimizer search method used for this sweep.',
        render: (sweep) => sweep.method ?? '-',
        mobileLabel: 'Method',
        mono: false,
      },
      {
        id: 'objective',
        header: 'Objective',
        align: 'left',
        help: 'The primary metric and direction this sweep optimizes.',
        render: (sweep) => objectiveLabel(sweep.objective),
        mobileLabel: 'Objective',
        mono: false,
      },
      {
        id: 'status',
        header: 'Status',
        align: 'left',
        help: 'Current state of the optimizer sweep.',
        render: (sweep) => (
          <span className={cn('rounded px-1.5 py-0.5 text-xs font-semibold ring-1', statusTone(sweep.status))}>
            {sweep.status}
          </span>
        ),
        mobileLabel: 'Status',
        mono: false,
      },
      {
        id: 'progress',
        header: 'Trials complete',
        align: 'left',
        help: 'Percentage of planned trials completed, as projected by the optimizer.',
        render: (sweep) => {
          const percent = Math.round(sweep.progress);
          return (
            <div className="flex items-center gap-2">
              <div className="h-1.5 w-24 overflow-hidden rounded-full bg-surface-2">
                <div className="h-full bg-accent" style={{ width: `${percent}%` }} />
              </div>
              <span className="tabular-nums text-xs text-ay-muted">
                {percent}%
              </span>
            </div>
          );
        },
        mobileLabel: 'Progress',
        mono: false,
      },
      {
        id: 'created',
        header: 'Created',
        align: 'left',
        help: 'When the sweep was submitted.',
        render: (sweep) => sweep.createdAt?.slice(0, 19).replace('T', ' ') ?? '-',
        mobileLabel: 'Created',
      },
      {
        id: 'reason',
        header: 'Failure reason',
        align: 'left',
        help: 'Why a failed sweep stopped, when the optimizer recorded a reason.',
        render: (sweep) => sweep.error ? <span className="text-xs text-bear">{sweep.error}</span> : '-',
        mobileLabel: 'Failure reason',
        mono: false,
      },
    ],
    [nameById], // eslint-disable-line react-hooks/exhaustive-deps
  );

  const page = Math.floor(offset / SWEEPS_PAGE_SIZE) + 1;
  const hasNext = rows.length === SWEEPS_PAGE_SIZE;

  return (
    <LoadBeat>
      <PageHeader
        title="Sweeps"
        subtitle="Optimizer sweeps, newest first"
        help="Tracks parameter sweeps launched from the backtest runner; open a sweep to inspect its trials and leaderboard."
      />
      <div className="mb-3 flex justify-end">
        <button
          type="button"
          onClick={() => sweeps.refetch()}
          disabled={sweeps.isFetching}
          className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent disabled:opacity-50"
        >
          {sweeps.isFetching ? '...' : 'Reload'}
        </button>
      </div>
      <BeatBlock>
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(sweep) => sweep.jobId}
          ariaLabel="Optimizer sweeps"
          emptyMessage={sweeps.isLoading ? 'Loading...' : 'No sweeps yet - launch one from the runner.'}
        />
      </BeatBlock>
      <div className="mt-3 flex items-center justify-end gap-2 text-sm">
        <span className="text-ay-muted">Page {page}</span>
        <button
          type="button"
          onClick={() => setOffset((current) => Math.max(0, current - SWEEPS_PAGE_SIZE))}
          disabled={offset === 0 || sweeps.isFetching}
          className="h-9 rounded-md border border-ay-border px-3 hover:border-accent disabled:opacity-40"
        >
          Prev
        </button>
        <button
          type="button"
          onClick={() => setOffset((current) => current + SWEEPS_PAGE_SIZE)}
          disabled={!hasNext || sweeps.isFetching}
          className="h-9 rounded-md border border-ay-border px-3 hover:border-accent disabled:opacity-40"
        >
          Next
        </button>
      </div>
    </LoadBeat>
  );
}

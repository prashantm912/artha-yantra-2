import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Tags } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { FIELD_HELP } from '../../core/fieldHelp.ts';
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

// DataTable column id → backend sort key (server-side sort so a header click sorts EVERY page, not
// just the loaded one). Columns without an entry (Test Window, Actions) aren't server-sortable.
const SORT_API: Record<string, string> = {
  job: 'id',
  strategy: 'strategyId',
  type: 'kind',
  status: 'status',
  return: 'totalReturn',
  progress: 'progress',
  created: 'createdAt',
};

export function JobsPage() {
  const [status, setStatus] = useState<string | null>(null);
  const [strategyId, setStrategyId] = useState<string | null>(null);
  const [tags, setTags] = useState<string[]>([]);
  // Server-side filter: keep only jobs whose pinned version is its strategy's NEWEST version. Sends the
  // set of current-version ids so the filter spans EVERY page, not just the loaded one.
  const [latestOnly, setLatestOnly] = useState(false);
  const [offset, setOffset] = useState(0);
  const [sort, setSort] = useState<{ id: string; dir: 'asc' | 'desc' } | null>({
    id: 'created',
    dir: 'desc',
  });
  const navigate = useNavigate();
  const strategies = useStrategies('', null);
  const apiSortBy = sort ? (SORT_API[sort.id] ?? 'createdAt') : 'createdAt';
  const apiSortDir = sort?.dir ?? 'desc';

  // Tag multi-select → the matching strategies' ids, filtered server-side (spans every page).
  // '__none__' = tags chosen but no strategy matched → the list comes back empty (not unfiltered).
  const tagStrategyIds = useMemo(() => {
    if (!tags.length) return null;
    const ids = (strategies.data?.items ?? [])
      .filter((s) => (s.tags ?? []).some((t) => tags.includes(t)))
      .map((s) => s.id);
    return ids.length ? ids.join(',') : '__none__';
  }, [tags, strategies.data]);

  // "Latest version only" → "strategyId:version" pairs, one per strategy's CURRENT version. The server
  // matches these against the job request JSONB (NOT the strategy_version_id column, which TRIAL +
  // OPTIMIZATION rows leave NULL), so the filter spans every page AND keeps trials/sweeps — exactly
  // what the row's is-latest badge shows. '__none__' = on but nothing to match → empty (not unfiltered).
  const currentVersionPairs = useMemo(() => {
    if (!latestOnly) return null;
    const pairs = (strategies.data?.items ?? [])
      .filter((s) => s.currentVersion)
      .map((s) => `${s.id}:${s.currentVersion}`);
    return pairs.length ? pairs.join(',') : '__none__';
  }, [latestOnly, strategies.data]);

  const q = useJobs(status, strategyId, offset, apiSortBy, apiSortDir, tagStrategyIds, currentVersionPairs);
  useJobsLive(status, strategyId, offset, apiSortBy, apiSortDir, tagStrategyIds, currentVersionPairs);
  const cancel = useCancelJob();

  const nameById = useMemo(
    () => new Map((strategies.data?.items ?? []).map((s) => [s.id, s.name])),
    [strategies.data],
  );
  // strategyId → the strategy's NEWEST version string (RegistryService.currentVersion = latestVersion);
  // a job is "latest" when its pinned version equals this.
  const latestVersionById = useMemo(
    () => new Map((strategies.data?.items ?? []).map((s) => [s.id, s.currentVersion])),
    [strategies.data],
  );
  const isLatest = (job: JobDto) => {
    const latest = job.strategyId ? latestVersionById.get(job.strategyId) : undefined;
    return !!job.strategyVersion && !!latest && job.strategyVersion === latest;
  };

  const rows = useMemo(() => q.data?.items ?? [], [q.data]);
  const allTags = useMemo(() => {
    const set = new Set<string>();
    for (const s of strategies.data?.items ?? []) for (const t of s.tags ?? []) set.add(t);
    return [...set].sort();
  }, [strategies.data]);
  const stratOptions = useMemo(
    () => (strategies.data?.items ?? []).map((s) => ({ value: s.id, label: s.name })),
    [strategies.data],
  );

  // Reset to the first page whenever a filter OR the sort changes (a stale offset can land past the set).
  useEffect(() => setOffset(0), [status, strategyId, tags, sort, latestOnly]);

  const viewResults = async (jobId: string) => {
    const ref = await fetchResultRef(jobId);
    if (ref) navigate(`/backtests/${ref}`);
  };

  // Compare picker (audit 2026-07-02 §10.2-3 / §11 item 15): tick 2-6 completed runs, press Compare —
  // resolves each job's resultRef and lands on /backtests/compare?ids=… (previously URL-only).
  const [compareIds, setCompareIds] = useState<string[]>([]);
  const toggleCompare = (jobId: string) =>
    setCompareIds((prev) =>
      prev.includes(jobId) ? prev.filter((x) => x !== jobId) : prev.length >= 6 ? prev : [...prev, jobId],
    );
  const goCompare = async () => {
    const refs = (await Promise.all(compareIds.map(fetchResultRef))).filter(
      (r): r is string => !!r,
    );
    if (refs.length >= 2) navigate(`/backtests/compare?ids=${refs.join(',')}`);
  };

  const strategyName = (job: JobDto) =>
    (job.strategyId ? nameById.get(job.strategyId) : null) ??
    (job.strategyId ? job.strategyId.slice(0, 8) : '—');

  const columns = useMemo<DataColumn<JobDto>[]>(
    () => [
      {
        id: 'compare',
        header: 'Cmp',
        align: 'center',
        help: 'Tick 2–6 completed backtests to compare, then press the Compare button above.',
        render: (job) =>
          job.kind === 'BACKTEST' && job.status === 'completed' ? (
            <input
              type="checkbox"
              aria-label={`Compare run ${job.jobId.slice(0, 8)}`}
              checked={compareIds.includes(job.jobId)}
              onChange={() => toggleCompare(job.jobId)}
              className="size-4 accent-accent"
            />
          ) : null,
        mono: false,
      },
      {
        id: 'job',
        header: 'Job',
        align: 'left',
        help: 'Short id of this job — the first 8 characters of its full identifier.',
        sortValue: (job) => job.jobId,
        sortType: 'text',
        render: (job) => <span className="font-mono text-xs">{job.jobId.slice(0, 8)}</span>,
        mono: false,
      },
      {
        id: 'strategy',
        header: 'Strategy',
        align: 'left',
        help: 'The strategy this job is testing.',
        sortValue: (job) => strategyName(job),
        sortType: 'text',
        render: (job) => <span className="text-sm">{strategyName(job)}</span>,
        mobileLabel: 'Strategy',
        mono: false,
      },
      {
        id: 'version',
        header: 'Version',
        align: 'left',
        help: 'The strategy version this job ran — "latest" if it matches the strategy\'s newest version, otherwise an older one.',
        // Not server-sortable: the version lives in the request JSONB, not a sortable jobs column.
        render: (job) =>
          job.strategyVersion ? (
            <span className="flex items-center gap-1.5">
              <span className="tabular-nums text-xs">v{job.strategyVersion}</span>
              {isLatest(job) ? (
                <span className="rounded bg-bull/15 px-1.5 py-0.5 text-[10px] font-semibold text-bull ring-1 ring-bull/40">
                  latest
                </span>
              ) : (
                <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[10px] text-ay-muted ring-1 ring-ay-border">
                  old
                </span>
              )}
            </span>
          ) : (
            <span className="text-ay-muted">—</span>
          ),
        mobileLabel: 'Version',
        mono: false,
      },
      {
        id: 'type',
        header: 'Type',
        align: 'left',
        help: 'Job kind — a single Backtest run or an Optimization sweep over many parameter sets.',
        sortValue: (job) => job.kind,
        sortType: 'text',
        render: (job) => (
          <span className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-muted">{job.kind}</span>
        ),
        mobileLabel: 'Type',
        mono: false,
      },
      {
        id: 'window',
        header: 'Test Window',
        align: 'left',
        help: 'The date range the run was tested over (from → to).',
        render: (job) =>
          job.testFrom || job.testTo ? (
            <span className="tabular-nums text-xs text-ay-muted">
              {job.testFrom?.slice(0, 10) ?? '—'} → {job.testTo?.slice(0, 10) ?? '—'}
            </span>
          ) : (
            <span className="text-ay-muted">—</span>
          ),
        mobileLabel: 'Test Window',
        mono: false,
      },
      {
        id: 'status',
        header: 'Status',
        align: 'left',
        help: 'Current state of the job — queued, running, completed, cancelling, or failed.',
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
        help: FIELD_HELP.totalReturn,
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
        help: 'How far the job has run, 0 to 100% — updates live without refreshing.',
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
        help: 'When the job was submitted.',
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
        help: 'Open the run results or sweep explorer, or cancel a queued/running job.',
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
    // nameById drives the strategy column's label, latestVersionById the is-latest badge,
    // compareIds the picker checkboxes; navigate/cancel/viewResults are stable.
    [nameById, latestVersionById, compareIds], // eslint-disable-line react-hooks/exhaustive-deps
  );

  const page = Math.floor(offset / JOBS_PAGE_SIZE) + 1;
  const hasNext = rows.length === JOBS_PAGE_SIZE;

  return (
    <LoadBeat>
      <PageHeader title="Jobs" subtitle="Live backtest and sweep jobs with per-row progress" help="Tracks every backtest and sweep you've launched with live progress; completed runs link to their results or sweep explorer." />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Select
          value={status}
          options={[...JOB_STATUSES]}
          onChange={(v) => setStatus(v || null)}
          ariaLabel="Status filter"
          placeholder="All statuses"
          title="Show only jobs in the chosen state (e.g. running, completed)."
        />
        <Select
          value={strategyId}
          options={stratOptions}
          onChange={(v) => setStrategyId(v || null)}
          ariaLabel="Strategy filter"
          placeholder="All strategies"
          className="max-w-[16rem]"
          title="Show only jobs for the chosen strategy."
        />
        {allTags.length > 0 && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                aria-label="Filter by strategy tag"
                title="Show only jobs whose strategy carries any of the selected tags."
                className="flex h-9 items-center gap-1.5 rounded-md border border-ay-border px-3 text-sm hover:border-accent"
              >
                <Tags className="size-4 text-ay-muted" aria-hidden="true" />
                {tags.length ? `${tags.length} tag${tags.length > 1 ? 's' : ''}` : 'Tags'}
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="max-h-72 overflow-auto">
              <DropdownMenuLabel>Filter by strategy tag</DropdownMenuLabel>
              <DropdownMenuSeparator />
              {allTags.map((t) => (
                <DropdownMenuCheckboxItem
                  key={t}
                  checked={tags.includes(t)}
                  onCheckedChange={(c) =>
                    setTags((prev) => (c ? [...prev, t] : prev.filter((x) => x !== t)))
                  }
                  onSelect={(e) => e.preventDefault()}
                >
                  {t}
                </DropdownMenuCheckboxItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        )}
        <label
          className="flex h-9 cursor-pointer items-center gap-1.5 rounded-md border border-ay-border px-3 text-sm hover:border-accent"
          title="Hide jobs that ran an older version — keep only jobs on each strategy's newest version (across all pages)."
        >
          <input
            type="checkbox"
            checked={latestOnly}
            onChange={(e) => setLatestOnly(e.target.checked)}
            className="size-4 accent-accent"
            aria-label="Latest version only"
          />
          Latest version only
        </label>
        {(status || strategyId || tags.length > 0 || latestOnly) && (
          <button
            type="button"
            onClick={() => {
              setStatus(null);
              setStrategyId(null);
              setTags([]);
              setLatestOnly(false);
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
        {compareIds.length > 0 && (
          <button
            type="button"
            onClick={() => void goCompare()}
            disabled={compareIds.length < 2}
            title="Open the metric matrix + overlaid equity curves for the ticked runs (2–6)."
            className="h-9 rounded-md border border-accent bg-accent/10 px-3 text-sm font-medium text-accent hover:bg-accent/20 disabled:opacity-50"
          >
            Compare ({compareIds.length})
          </button>
        )}
      </div>

      <BeatBlock>
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(job) => job.jobId}
          ariaLabel="Jobs"
          manualSorting
          sortState={sort}
          onSortChange={setSort}
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

import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle, Bookmark, Pencil, StickyNote, Tags, Trash2, X } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { Button } from '../../components/atoms/Button.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu.tsx';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { FIELD_HELP } from '../../core/fieldHelp.ts';
import { useStrategies } from '../../api/strategies.ts';
import {
  JOB_STATUSES,
  JOBS_PAGE_SIZE,
  fetchResultRef,
  useAnnotateJob,
  useCancelJob,
  useCreateSavedView,
  useDeleteSavedView,
  useJobDetail,
  useJobs,
  useJobsLive,
  useSavedViews,
  type JobDto,
  type JobStatus,
  type SavedView,
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

// Failed-job detail dialog: the jobs LIST omits `jobs.error`, so a failed row's message is fetched
// lazily from the detail endpoint (audit §2.7 — the BE already serves it). Accessible modal (Radix
// titled + described) so the failure text is readable at any length, not a truncated title attr.
function JobErrorDialog({
  jobId,
  onClose,
  returnFocusTo,
}: {
  jobId: string;
  onClose: () => void;
  returnFocusTo: HTMLElement | null;
}) {
  const detail = useJobDetail(jobId);
  return (
    <Dialog
      open
      onOpenChange={(o) => {
        if (!o) onClose();
      }}
    >
      {/* No DialogTrigger exists (the badge is a plain button), so Radix's default close handler
          would drop focus to <body> (WCAG 2.4.3) — restore it to the invoking badge ourselves. */}
      <DialogContent
        onCloseAutoFocus={(e) => {
          e.preventDefault();
          returnFocusTo?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>Job failed</DialogTitle>
          <DialogDescription>
            Run {jobId.slice(0, 8)} — why the backtest or sweep did not complete.
          </DialogDescription>
        </DialogHeader>
        {detail.isPending ? (
          <p role="status" className="text-caption text-ay-muted">
            Loading the failure detail…
          </p>
        ) : detail.isError ? (
          <p role="alert" className="text-caption text-bear">
            Couldn't load the failure detail.
          </p>
        ) : detail.data?.error ? (
          // Focusable scroll region (axe scrollable-region-focusable — the DataTable idiom): long
          // errors overflow the max-height and keyboard users must be able to scroll them.
          <pre
            tabIndex={0}
            role="region"
            aria-label="Failure message"
            className="max-h-72 overflow-auto whitespace-pre-wrap break-words rounded border border-bear/40 bg-surface-1 p-3 text-caption text-bear"
          >
            {detail.data.error}
          </pre>
        ) : (
          <p className="text-caption text-ay-muted">No error message was recorded for this failure.</p>
        )}
      </DialogContent>
    </Dialog>
  );
}

// The saved-view `kind` + the opaque filter payload we persist: the CURRENT Jobs-page filter set.
// NOTE: this is the NEW per-job `jobTag` filter, NOT the strategy-tag multiselect (`tags`) — the two
// stay deliberately separate (see the naming trap in the D4 brief).
const SAVED_VIEW_KIND = 'backtest_jobs';
interface JobsViewFilter {
  status: string | null;
  strategyId: string | null;
  jobTag: string | null;
  latestOnly: boolean;
  sort: { id: string; dir: 'asc' | 'desc' } | null;
}

// Per-job annotation editor (D4 P2-2): edit the run's TAGS (chips add/remove) + free-text NOTE, then
// PATCH .../annotations (replaces both). Reuses the accessible Radix Dialog; the server validates the
// caps and the global mutation-error toast surfaces a 422. Focus returns to the invoking pencil.
function JobAnnotateDialog({
  job,
  onClose,
  returnFocusTo,
}: {
  job: JobDto;
  onClose: () => void;
  returnFocusTo: HTMLElement | null;
}) {
  const annotate = useAnnotateJob();
  const [tagList, setTagList] = useState<string[]>(job.tags ?? []);
  const [draft, setDraft] = useState('');
  const [note, setNote] = useState(job.note ?? '');

  const addTag = () => {
    const t = draft.trim();
    if (!t) return;
    setTagList((prev) => (prev.includes(t) ? prev : [...prev, t]));
    setDraft('');
  };
  const removeTag = (t: string) => setTagList((prev) => prev.filter((x) => x !== t));
  const save = () =>
    annotate.mutate(
      { jobId: job.jobId, tags: tagList, note: note.trim() || null },
      { onSuccess: () => onClose() },
    );

  return (
    <Dialog
      open
      onOpenChange={(o) => {
        if (!o) onClose();
      }}
    >
      <DialogContent
        onCloseAutoFocus={(e) => {
          e.preventDefault();
          returnFocusTo?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>Tags &amp; note</DialogTitle>
          <DialogDescription>Label and annotate run {job.jobId.slice(0, 8)}.</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          <div>
            <div className="mb-1.5 flex flex-wrap gap-1">
              {tagList.length === 0 ? (
                <span className="text-caption text-ay-muted">No tags yet.</span>
              ) : (
                tagList.map((t) => (
                  <span
                    key={t}
                    className="inline-flex items-center gap-1 rounded bg-surface-2 px-1.5 py-0.5 text-caption text-ay-text ring-1 ring-ay-border"
                  >
                    {t}
                    <button
                      type="button"
                      aria-label={`Remove tag ${t}`}
                      onClick={() => removeTag(t)}
                      className="text-ay-muted hover:text-bear focus-visible:outline focus-visible:outline-1 focus-visible:outline-accent"
                    >
                      <X aria-hidden="true" className="size-3" />
                    </button>
                  </span>
                ))
              )}
            </div>
            <div className="flex gap-2">
              <input
                type="text"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ',') {
                    e.preventDefault();
                    addTag();
                  }
                }}
                aria-label="Add a tag"
                placeholder="Add a tag, press Enter"
                className="h-9 flex-1 rounded-md border border-ay-border bg-surface-1 px-3 text-sm text-ay-text focus-visible:border-accent focus-visible:outline-none"
              />
              <Button type="button" variant="outline" size="sm" onClick={addTag} disabled={!draft.trim()}>
                Add
              </Button>
            </div>
          </div>
          <label className="flex flex-col gap-1 text-caption text-ay-muted">
            Note
            <textarea
              value={note}
              onChange={(e) => setNote(e.target.value)}
              aria-label="Note"
              rows={4}
              className="rounded-md border border-ay-border bg-surface-1 p-2 text-sm text-ay-text focus-visible:border-accent focus-visible:outline-none"
            />
          </label>
        </div>
        <DialogFooter showCloseButton>
          <Button type="button" onClick={save} loading={annotate.isPending}>
            Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// Save-view dialog (D4 P2-2): name the CURRENT Jobs-page filter set → POST /saved-views. A duplicate
// (owner, kind, name) 409s server-side and surfaces via the global mutation-error toast.
function SaveViewDialog({
  filter,
  onClose,
  returnFocusTo,
}: {
  filter: JobsViewFilter;
  onClose: () => void;
  returnFocusTo: HTMLElement | null;
}) {
  const create = useCreateSavedView();
  const [name, setName] = useState('');
  const save = () => {
    const n = name.trim();
    if (!n) return;
    create.mutate({ kind: SAVED_VIEW_KIND, name: n, filter }, { onSuccess: () => onClose() });
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => {
        if (!o) onClose();
      }}
    >
      <DialogContent
        onCloseAutoFocus={(e) => {
          e.preventDefault();
          returnFocusTo?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>Save this view</DialogTitle>
          <DialogDescription>Save the current filters under a name to reload later.</DialogDescription>
        </DialogHeader>
        <label className="flex flex-col gap-1 text-caption text-ay-muted">
          View name
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                save();
              }
            }}
            aria-label="View name"
            className="h-9 rounded-md border border-ay-border bg-surface-1 px-3 text-sm text-ay-text focus-visible:border-accent focus-visible:outline-none"
          />
        </label>
        <DialogFooter showCloseButton>
          <Button type="button" onClick={save} loading={create.isPending} disabled={!name.trim()}>
            Save view
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

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
  // NEW (D4 P2-2): per-JOB annotation-tag filter → the backend `?tag=` param (single tag). This is a
  // SEPARATE control from the strategy-tag multiselect `tags` above — do not conflate the two.
  const [jobTagFilter, setJobTagFilter] = useState<string | null>(null);
  // The failed job whose error dialog is open (null = closed). The list omits `error` → fetched lazily.
  const [errorJobId, setErrorJobId] = useState<string | null>(null);
  const errorTriggerRef = useRef<HTMLElement | null>(null);
  // The job whose tags/note editor is open (null = closed); focus returns to the invoking pencil.
  const [annotateJob, setAnnotateJob] = useState<JobDto | null>(null);
  const annotateTriggerRef = useRef<HTMLElement | null>(null);
  // Saved-views: the save-name dialog open flag + the load/delete store.
  const [saveViewOpen, setSaveViewOpen] = useState(false);
  const saveViewTriggerRef = useRef<HTMLElement | null>(null);
  const savedViews = useSavedViews(SAVED_VIEW_KIND);
  const deleteView = useDeleteSavedView();
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

  const q = useJobs(status, strategyId, offset, apiSortBy, apiSortDir, tagStrategyIds, currentVersionPairs, jobTagFilter);
  useJobsLive(status, strategyId, offset, apiSortBy, apiSortDir, tagStrategyIds, currentVersionPairs, jobTagFilter);
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
  useEffect(() => setOffset(0), [status, strategyId, tags, sort, latestOnly, jobTagFilter]);

  // Saved-views: the CURRENT filter set we persist, and applying a stored one back onto page state.
  const currentFilter: JobsViewFilter = { status, strategyId, jobTag: jobTagFilter, latestOnly, sort };
  const applyView = (view: SavedView) => {
    const f = (view.filter ?? {}) as Partial<JobsViewFilter>;
    setStatus(f.status ?? null);
    setStrategyId(f.strategyId ?? null);
    setJobTagFilter(f.jobTag ?? null);
    setLatestOnly(!!f.latestOnly);
    setSort(f.sort ?? null);
  };

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

  // A job whose strategy was deleted resolves to no name — label it honestly, not a bare hash (§3).
  const strategyName = (job: JobDto) =>
    (job.strategyId ? nameById.get(job.strategyId) : null) ??
    (job.strategyId ? `deleted · ${job.strategyId.slice(0, 8)}` : '—');

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
        help: 'Current state of the job — queued, running, completed, cancelling, or failed. Click a failed badge to see why it failed.',
        sortValue: (job) => job.status,
        sortType: 'text',
        render: (job) =>
          job.status === 'failed' ? (
            // Row-level failure indicator: the failed badge is a button that opens the error dialog.
            <button
              type="button"
              onClick={(e) => {
                errorTriggerRef.current = e.currentTarget; // focus returns here on dialog close
                setErrorJobId(job.jobId);
              }}
              aria-label={`Show why run ${job.jobId.slice(0, 8)} failed`}
              className={cn(
                'inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs font-semibold ring-1 hover:ring-2 focus-visible:outline focus-visible:outline-1 focus-visible:outline-accent',
                statusTone(job.status),
              )}
            >
              {job.status}
              <AlertTriangle aria-hidden="true" className="size-3" />
            </button>
          ) : (
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
        id: 'tags',
        header: 'Tags',
        align: 'left',
        help: 'Your own labels + note on this run (distinct from the strategy tags). Click the pencil to edit.',
        render: (job) => (
          <div className="flex items-center gap-1.5">
            <div className="flex flex-wrap gap-1">
              {(job.tags ?? []).map((t) => (
                <span
                  key={t}
                  className="rounded bg-surface-2 px-1.5 py-0.5 text-[10px] text-ay-muted ring-1 ring-ay-border"
                >
                  {t}
                </span>
              ))}
            </div>
            {job.note ? (
              <span title={job.note}>
                <StickyNote role="img" aria-label="This run has a note" className="size-3.5 text-ay-muted" />
              </span>
            ) : null}
            <button
              type="button"
              onClick={(e) => {
                annotateTriggerRef.current = e.currentTarget; // focus returns here on dialog close
                setAnnotateJob(job);
              }}
              aria-label={`Edit tags and note for job ${job.jobId.slice(0, 8)}`}
              className="rounded p-0.5 text-ay-muted hover:text-accent focus-visible:outline focus-visible:outline-1 focus-visible:outline-accent"
            >
              <Pencil aria-hidden="true" className="size-3.5" />
            </button>
          </div>
        ),
        mobileLabel: 'Tags',
        mono: false,
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
        <input
          type="text"
          value={jobTagFilter ?? ''}
          onChange={(e) => setJobTagFilter(e.target.value || null)}
          aria-label="Filter by job tag"
          placeholder="Job tag…"
          title="Show only runs you annotated with this job tag (a single tag)."
          className="h-9 w-36 rounded-md border border-ay-border bg-surface-1 px-3 text-sm text-ay-text focus-visible:border-accent focus-visible:outline-none"
        />
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
        {(status || strategyId || tags.length > 0 || latestOnly || jobTagFilter) && (
          <button
            type="button"
            onClick={() => {
              setStatus(null);
              setStrategyId(null);
              setTags([]);
              setLatestOnly(false);
              setJobTagFilter(null);
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
        <button
          type="button"
          onClick={(e) => {
            saveViewTriggerRef.current = e.currentTarget;
            setSaveViewOpen(true);
          }}
          title="Save the current filters under a name to reload later."
          className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent"
        >
          Save view
        </button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              aria-label="Load a saved view"
              title="Load or delete one of your saved filter sets."
              className="flex h-9 items-center gap-1.5 rounded-md border border-ay-border px-3 text-sm hover:border-accent"
            >
              <Bookmark className="size-4 text-ay-muted" aria-hidden="true" />
              {savedViews.data?.items?.length ? `Views (${savedViews.data.items.length})` : 'Views'}
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="max-h-72 w-56 overflow-auto">
            <DropdownMenuLabel>Saved views</DropdownMenuLabel>
            <DropdownMenuSeparator />
            {(savedViews.data?.items ?? []).length === 0 ? (
              <DropdownMenuItem disabled>No saved views yet</DropdownMenuItem>
            ) : (
              (savedViews.data?.items ?? []).map((v) => (
                <div key={v.id} className="flex items-center">
                  <DropdownMenuItem className="flex-1" onSelect={() => applyView(v)}>
                    {v.name}
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    aria-label={`Delete saved view ${v.name}`}
                    className="text-bear"
                    onSelect={(e) => {
                      e.preventDefault(); // keep the menu open so several can be deleted
                      deleteView.mutate(v.id);
                    }}
                  >
                    <Trash2 aria-hidden="true" className="size-3.5" />
                  </DropdownMenuItem>
                </div>
              ))
            )}
          </DropdownMenuContent>
        </DropdownMenu>
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

      {errorJobId && (
        <JobErrorDialog
          jobId={errorJobId}
          onClose={() => setErrorJobId(null)}
          returnFocusTo={errorTriggerRef.current}
        />
      )}

      {annotateJob && (
        <JobAnnotateDialog
          job={annotateJob}
          onClose={() => setAnnotateJob(null)}
          returnFocusTo={annotateTriggerRef.current}
        />
      )}

      {saveViewOpen && (
        <SaveViewDialog
          filter={currentFilter}
          onClose={() => setSaveViewOpen(false)}
          returnFocusTo={saveViewTriggerRef.current}
        />
      )}
    </LoadBeat>
  );
}

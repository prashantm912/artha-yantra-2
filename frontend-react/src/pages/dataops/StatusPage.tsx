import { useState } from 'react';
import {
  useBackfillJobs,
  useExpiredStatus,
  useOiStatus,
  useQuota,
  type BackfillJobRow,
  type BackfillState,
  type ExpiredStatus,
  type OiStatus,
} from '../../api/dataops.ts';
import { LogFeed } from '../../components/dataops/LogFeed.tsx';
import { Modal } from '../../components/dataops/Modal.tsx';
import { QuotaGauge } from '../../components/dataops/QuotaGauge.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { cn } from '../../lib/cn.ts';

// B1 Collection Status (route /data-ops/status). Read-only progress board over the two backfill jobs
// (useExpiredStatus / useOiStatus, both poll while RUNNING) plus the Upstox quota gauge (useQuota).
// The RUNNING bar is INDETERMINATE by design: the status payload exposes no known total (expiry count
// is discovered as the job walks), so there is no denominator to size a real progress fill — we show a
// thin animate-pulse accent bar rather than a fake percentage.

/** Badge ring/text colour per backfill state. */
function stateBadgeClass(state: BackfillState): string {
  switch (state) {
    case 'RUNNING':
      return 'text-accent ring-accent/40';
    case 'OK':
      return 'text-bull ring-bull/40';
    case 'FAILED':
      return 'text-bear ring-bear/40';
    case 'NEVER_RUN':
      return 'text-ay-muted ring-ay-border';
  }
}

function StateBadge({ state }: { state: BackfillState }) {
  return (
    <span
      className={cn(
        'inline-block rounded px-2 py-0.5 text-xs font-semibold ring-1',
        stateBadgeClass(state),
      )}
    >
      {state.replace('_', ' ')}
    </span>
  );
}

const fmt = (n: number): string => n.toLocaleString();

/** A label+value stat pill (big tabular value over a small muted label). */
function StatPill({ label, value }: { label: string; value: string }) {
  return (
    <div className="card shadow-e1">
      <div className="nums text-lg font-semibold text-ay-text">{value}</div>
      <div className="text-caption uppercase tracking-wide text-ay-muted">{label}</div>
    </div>
  );
}

function ExpiredCard({ expired }: { expired: ExpiredStatus }) {
  const [detailsOpen, setDetailsOpen] = useState(false);
  const running = expired.state === 'RUNNING';
  const logs = expired.recentLogs ?? [];

  return (
    <section className="rounded border border-ay-border bg-surface-1 p-3" aria-label="Expired backfill status">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-h3 text-ay-text">Expired backfill</h2>
          <StateBadge state={expired.state} />
          {running && expired.currentExpiry && (
            <span className="text-xs text-ay-muted">
              current expiry: <span className="text-ay-text">{expired.currentExpiry}</span>
            </span>
          )}
        </div>
        <button
          type="button"
          onClick={() => setDetailsOpen(true)}
          title="Open the full job detail panel (job ID, state, and recent logs)"
          className="rounded-md border border-ay-border bg-surface-1 px-3 py-1.5 text-sm text-ay-text hover:border-accent"
        >
          Details
        </button>
      </div>

      {running && (
        // Indeterminate: no known total (expiry count is discovered as the job runs), so no real fill %.
        <div
          className="mb-3 h-1 w-full overflow-hidden rounded-full bg-surface-2"
          role="progressbar"
          aria-label="Expired backfill in progress (indeterminate)"
        >
          <div className="h-1 w-full animate-pulse rounded-full bg-accent motion-reduce:animate-none" />
        </div>
      )}

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
        <StatPill label="Expiries" value={fmt(expired.expiries)} />
        <StatPill label="Contracts" value={fmt(expired.contracts)} />
        <StatPill label="Candle rows" value={fmt(expired.candleRows)} />
        <StatPill label="Written" value={fmt(expired.legsWritten)} />
        <StatPill label="Skipped" value={fmt(expired.legsSkipped)} />
        <StatPill label="Failed" value={fmt(expired.legsFailed)} />
      </div>

      {!running && (
        <p className="mt-2 text-xs text-ay-muted">
          Duration {(expired.durationMs / 1000).toLocaleString()} s
          {expired.lastRun && <> · last run {expired.lastRun}</>}
        </p>
      )}

      {expired.error && <p className="mt-2 text-xs text-bear">{expired.error}</p>}

      <div className="mt-3">
        <LogFeed lines={logs} />
      </div>

      <Modal open={detailsOpen} onClose={() => setDetailsOpen(false)} title="Expired backfill details">
        <dl className="mb-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
          <dt className="text-ay-muted">Job ID</dt>
          <dd className="font-mono text-ay-text">{expired.jobId ?? '—'}</dd>
          <dt className="text-ay-muted">State</dt>
          <dd className="text-ay-text">{expired.state.replace('_', ' ')}</dd>
          {expired.error && (
            <>
              <dt className="text-ay-muted">Error</dt>
              <dd className="text-bear">{expired.error}</dd>
            </>
          )}
        </dl>
        <LogFeed lines={logs} />
      </Modal>
    </section>
  );
}

function OiCard({ oi }: { oi: OiStatus }) {
  return (
    <section className="rounded border border-ay-border bg-surface-1 p-3" aria-label="OI backfill status">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <h2 className="text-h3 text-ay-text">OI backfill</h2>
        <StateBadge state={oi.state} />
      </div>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <StatPill label="Option rows" value={fmt(oi.optionRows)} />
        <StatPill label="Futures rows" value={fmt(oi.futuresRows)} />
        <StatPill label="Underlying" value={oi.underlying ?? '—'} />
        <StatPill label="Expiry" value={oi.expiry ?? '—'} />
      </div>
      <p className="mt-2 text-xs text-ay-muted">
        Session {oi.session ?? '—'}
        {!!oi.durationMs && <> · {(oi.durationMs / 1000).toLocaleString()} s</>}
        {oi.lastRun && <> · last run {oi.lastRun}</>}
      </p>
      {oi.error && <p className="mt-2 text-xs text-bear">{oi.error}</p>}
    </section>
  );
}

/** Ring/text colour per run-audit status (COMPLETED/RUNNING/FAILED — distinct from the live BackfillState). */
function jobStatusClass(status: string): string {
  switch (status) {
    case 'RUNNING':
      return 'text-accent ring-accent/40';
    case 'COMPLETED':
      return 'text-bull ring-bull/40';
    case 'FAILED':
      return 'text-bear ring-bear/40';
    default:
      return 'text-ay-muted ring-ay-border';
  }
}

/** Short local timestamp for a table cell (ISO in → locale string), or an em dash for null. */
function ts(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

// B1 history columns (kind · status · rows · timings · error). Same content/order as the hand-rolled
// table it replaced — DataTable adds zebra + sticky header + the mobile card list.
const backfillColumns: DataColumn<BackfillJobRow>[] = [
  { id: 'kind', header: 'Kind', align: 'left', mobileLabel: 'Kind', render: (job) => job.kind },
  {
    id: 'status',
    header: 'Status',
    align: 'left',
    mobileLabel: 'Status',
    render: (job) => (
      <span
        className={cn(
          'inline-block rounded px-2 py-0.5 text-xs font-semibold ring-1',
          jobStatusClass(job.status),
        )}
      >
        {job.status}
      </span>
    ),
  },
  {
    id: 'rows',
    header: 'Rows',
    align: 'right',
    mobileLabel: 'Rows',
    cellClassName: () => 'text-ay-text',
    render: (job) => (job.rowsWritten == null ? '—' : fmt(job.rowsWritten)),
  },
  {
    id: 'started',
    header: 'Started',
    align: 'left',
    mobileLabel: 'Started',
    cellClassName: () => 'text-ay-muted',
    render: (job) => ts(job.startedAt),
  },
  {
    id: 'finished',
    header: 'Finished',
    align: 'left',
    mobileLabel: 'Finished',
    cellClassName: () => 'text-ay-muted',
    render: (job) => ts(job.finishedAt),
  },
  {
    id: 'error',
    header: 'Error',
    align: 'left',
    mobileLabel: 'Error',
    cellClassName: () => 'text-bear',
    render: (job) => job.error ?? '',
  },
];

/** B1 history: the recent backfill runs from the V030 audit ledger (kind · status · rows · timings · error). */
function BackfillHistoryCard({ jobs }: { jobs: BackfillJobRow[] }) {
  return (
    <section
      className="rounded border border-ay-border bg-surface-1 p-3"
      aria-label="Backfill run history"
    >
      <h2 className="mb-2 text-h3 text-ay-text">Backfill history</h2>
      {jobs.length === 0 ? (
        <p className="text-xs text-ay-muted">No backfill runs recorded yet.</p>
      ) : (
        <DataTable
          columns={backfillColumns}
          rows={jobs}
          rowKey={(job) => String(job.id)}
          ariaLabel="Backfill history"
        />
      )}
    </section>
  );
}

export function StatusPage() {
  const expiredQ = useExpiredStatus();
  const oiQ = useOiStatus();
  const quotaQ = useQuota();
  const jobsQ = useBackfillJobs();

  return (
    <LoadBeat>
      <PageHeader
        title="Collection Status"
        help="Live progress of the expired-contract and OI backfill jobs, with your Upstox data quota; a running job shows an indeterminate bar because the total work isn't known until it finishes."
      />

      <BeatBlock className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <QueryState query={expiredQ} isEmpty={() => false}>
            {(expired) => <ExpiredCard expired={expired} />}
          </QueryState>
          <QueryState query={oiQ} isEmpty={() => false} errorTitle="Couldn't load OI backfill status">
            {(oi) => <OiCard oi={oi} />}
          </QueryState>
        </div>
        <div>
          <QueryState query={quotaQ} isEmpty={() => false} errorTitle="Couldn't load Upstox quota">
            {(quota) => <QuotaGauge configured={quota.configured} windows={quota.windows} />}
          </QueryState>
        </div>
      </BeatBlock>

      <BeatBlock>
        <QueryState
          query={jobsQ}
          isEmpty={() => false}
          errorTitle="Couldn't load backfill history"
        >
          {(jobs) => <BackfillHistoryCard jobs={jobs} />}
        </QueryState>
      </BeatBlock>
    </LoadBeat>
  );
}

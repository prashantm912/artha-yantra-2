import { useState } from 'react';
import {
  useExpiredStatus,
  useOiStatus,
  useQuota,
  type BackfillState,
  type ExpiredStatus,
  type OiStatus,
} from '../../api/dataops.ts';
import { LogFeed } from '../../components/dataops/LogFeed.tsx';
import { Modal } from '../../components/dataops/Modal.tsx';
import { QuotaGauge } from '../../components/dataops/QuotaGauge.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
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
          <h2 className="text-sm font-semibold text-ay-text">Expired backfill</h2>
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
        <h2 className="text-sm font-semibold text-ay-text">OI backfill</h2>
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

export function StatusPage() {
  const expiredQ = useExpiredStatus();
  const oiQ = useOiStatus();
  const quotaQ = useQuota();

  return (
    <div>
      <PageHeader title="Collection Status" />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
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
      </div>
    </div>
  );
}

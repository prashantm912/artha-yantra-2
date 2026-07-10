import {
  useIngestHealth,
  type BoardReport,
  type IngestStatus,
  type LastRun,
  type SourceHealth,
} from '../../api/ingestHealth.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { cn } from '../../lib/cn.ts';

// A11 EOD ingest health board (route /data-ops/ingest-health). A read-only view over the
// marketdata.ingest_runs ledger: per expected batch source, its last run, the health verdict for the
// most recent settled trading day, and how many of the last N trading days it silently missed. The
// per-day GREEN/YELLOW/RED verdicts are computed server-side by the A5 coverage canary's policy.

/** Text/ring tone per health verdict. The badge always shows the WORD, so colour is never the sole cue. */
function statusTone(status: IngestStatus): string {
  switch (status) {
    case 'GREEN':
      return 'text-bull ring-bull/40';
    case 'YELLOW':
      return 'text-warn ring-warn/40';
    case 'RED':
      return 'text-bear ring-bear/40';
  }
}

/** Background tone for a day-strip cell (decorative — the day + verdict also ride the cell's title). */
function dayTone(status: IngestStatus): string {
  switch (status) {
    case 'GREEN':
      return 'bg-bull';
    case 'YELLOW':
      return 'bg-warn';
    case 'RED':
      return 'bg-bear';
  }
}

function StatusBadge({ status }: { status: IngestStatus }) {
  return (
    <span
      className={cn(
        'inline-block rounded px-2 py-0.5 text-xs font-semibold ring-1',
        statusTone(status),
      )}
    >
      {status}
    </span>
  );
}

/** A row of small squares, one per trading day (newest → oldest), coloured by that day's verdict. */
function DayStrip({ days }: { days: SourceHealth['days'] }) {
  if (days.length === 0) return <span className="text-ay-muted">—</span>;
  return (
    <span className="inline-flex gap-0.5" role="img" aria-label={`Last ${days.length} trading days`}>
      {days.map((d) => (
        <span
          key={d.day}
          title={`${d.day}: ${d.status}`}
          aria-label={`${d.day}: ${d.status}`}
          className={cn('size-3 rounded-[2px]', dayTone(d.status))}
        />
      ))}
    </span>
  );
}

/** Short local timestamp for a cell (ISO in → locale string), or an em dash for null. */
function ts(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

/** Last-run cell: terminal status word + when it ran, with a crashed-mid-flight marker for a stale run. */
function LastRunCell({ lastRun }: { lastRun: LastRun | null }) {
  if (!lastRun) return <span className="text-ay-muted">never run</span>;
  const tone = lastRun.stale
    ? 'text-bear'
    : lastRun.status === 'SUCCESS'
      ? 'text-bull'
      : lastRun.status === 'FAILURE'
        ? 'text-bear'
        : 'text-accent';
  return (
    <span className="inline-flex flex-col">
      <span className={cn('font-semibold', tone)}>
        {lastRun.status}
        {lastRun.stale && <span className="text-bear"> · stale</span>}
      </span>
      <span className="text-caption text-ay-muted">{ts(lastRun.finishedAt ?? lastRun.startedAt)}</span>
    </span>
  );
}

/** Severity rank so a status-sorted table surfaces the worst sources first. */
const SEVERITY: Record<IngestStatus, number> = { RED: 2, YELLOW: 1, GREEN: 0 };

const columns: DataColumn<SourceHealth>[] = [
  {
    id: 'source',
    header: 'Source',
    pin: 'left',
    mobileLabel: 'Source',
    render: (r) => (
      <span className="inline-flex flex-col">
        <span className="font-medium text-ay-text">{r.source}</span>
        <span className="text-caption text-ay-muted">{r.policy}</span>
      </span>
    ),
    sortValue: (r) => r.source,
    sortType: 'text',
  },
  {
    id: 'status',
    header: 'Status',
    align: 'center',
    mobileLabel: 'Status',
    render: (r) => <StatusBadge status={r.status} />,
    sortValue: (r) => SEVERITY[r.status],
  },
  {
    id: 'recent',
    header: 'Recent days',
    help: 'One square per trading day, newest on the left. Hover a square for its date and verdict.',
    render: (r) => <DayStrip days={r.days} />,
  },
  {
    id: 'missing',
    header: 'Missing',
    align: 'right',
    mobileLabel: 'Missing',
    help: 'Trading days in the window with no healthy ingest (RED) — the silent-hole count.',
    render: (r) => (
      <span className={cn('tabular-nums', r.missingDays > 0 ? 'text-bear' : 'text-ay-muted')}>
        {r.missingDays}
      </span>
    ),
    sortValue: (r) => r.missingDays,
  },
  {
    id: 'lastRun',
    header: 'Last run',
    mobileLabel: 'Last run',
    render: (r) => <LastRunCell lastRun={r.lastRun} />,
    sortValue: (r) => r.lastRun?.finishedAt ?? r.lastRun?.startedAt ?? '',
    sortType: 'text',
  },
  {
    id: 'rows',
    header: 'Rows',
    align: 'right',
    mobileLabel: 'Rows',
    render: (r) =>
      r.lastRun?.rowsWritten == null ? (
        <span className="text-ay-muted">—</span>
      ) : (
        <span className="tabular-nums text-ay-text">{r.lastRun.rowsWritten.toLocaleString()}</span>
      ),
    sortValue: (r) => r.lastRun?.rowsWritten ?? null,
  },
  {
    id: 'detail',
    header: 'Detail',
    render: (r) => <span className="text-ay-muted">{r.detail}</span>,
  },
];

/** Green/yellow/red source counts for the summary strip. */
function tally(sources: SourceHealth[]): Record<IngestStatus, number> {
  return sources.reduce(
    (acc, s) => {
      acc[s.status] += 1;
      return acc;
    },
    { GREEN: 0, YELLOW: 0, RED: 0 } as Record<IngestStatus, number>,
  );
}

function SummaryStrip({ board }: { board: BoardReport }) {
  const counts = tally(board.sources);
  const window =
    board.fromDay && board.toDay
      ? `${board.fromDay} → ${board.toDay} · ${board.tradingDays} trading days`
      : 'calendar coverage unavailable — showing last run only';
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
      <span className="text-ay-muted">{window}</span>
      <span className="inline-flex items-center gap-1.5">
        <span className="size-3 rounded-[2px] bg-bull" aria-hidden="true" />
        <span className="text-ay-text">{counts.GREEN} healthy</span>
      </span>
      <span className="inline-flex items-center gap-1.5">
        <span className="size-3 rounded-[2px] bg-warn" aria-hidden="true" />
        <span className="text-ay-text">{counts.YELLOW} degraded</span>
      </span>
      <span className="inline-flex items-center gap-1.5">
        <span className="size-3 rounded-[2px] bg-bear" aria-hidden="true" />
        <span className="text-ay-text">{counts.RED} failing</span>
      </span>
    </div>
  );
}

export function IngestHealthPage() {
  const boardQ = useIngestHealth();

  return (
    <LoadBeat>
      <PageHeader
        title="Ingest Health"
        help="Per-source EOD ingest health from the ingest_runs ledger: each batch source's last run, its verdict for the most recent settled trading day, and how many of the last ~10 trading days it silently missed. GREEN = healthy, YELLOW = ran but starved (e.g. an empty screen), RED = missing or crashed."
      />

      <BeatBlock>
        <QueryState
          query={boardQ}
          isEmpty={(b) => b.sources.length === 0}
          errorTitle="Couldn't load ingest health"
        >
          {(board) => (
            <div className="space-y-3">
              <SummaryStrip board={board} />
              <DataTable
                columns={columns}
                rows={board.sources}
                rowKey={(r) => r.source}
                ariaLabel="Ingest health by source"
                initialSort={{ id: 'missing', dir: 'desc' }}
                emptyMessage="No ingest sources reported."
              />
            </div>
          )}
        </QueryState>
      </BeatBlock>
    </LoadBeat>
  );
}

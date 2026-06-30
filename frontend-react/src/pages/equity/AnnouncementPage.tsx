import { useMemo, useState } from 'react';
import { ExternalLink, Megaphone } from 'lucide-react';
import { useAnnouncements } from '../../api/announcements.ts';
import type { Announcement } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// Announcement (oipulse §equity/announcement): the NSE corporate filings feed — searchable by symbol and
// date range, with the full text and the announcement PDF. Date | Time | Name | Subject | Detail |
// Attachment, newest first, paginated. The filter (date range + symbol) is committed on Go so the NSE
// call fires once per query, not on every keystroke. The attachment opens the NSE PDF in a new tab
// (rel="noopener"). Empty when the NSE source is not live (mock) — the page renders its empty state.

/** Local (not UTC) ISO yyyy-MM-dd so the default range matches the user's calendar day. */
function isoLocal(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
function defaultRange(): { from: string; to: string } {
  const to = new Date();
  const from = new Date();
  from.setDate(from.getDate() - 7);
  return { from: isoLocal(from), to: isoLocal(to) };
}

const COLUMNS: DataColumn<Announcement>[] = [
  {
    id: 'date',
    header: 'Date',
    align: 'left',
    pin: 'left',
    sortValue: (r) => r.date,
    sortType: 'text',
    render: (r) => <span className="nums whitespace-nowrap text-ay-text">{r.date}</span>,
    mobileLabel: 'Date',
    help: 'The announcement date (IST).',
  },
  {
    id: 'time',
    header: 'Time',
    align: 'left',
    sortValue: (r) => r.time,
    sortType: 'text',
    render: (r) => <span className="nums whitespace-nowrap text-ay-muted">{r.time || '—'}</span>,
    mobileLabel: 'Time',
    help: 'The announcement time (IST, HH:mm:ss).',
  },
  {
    id: 'symbol',
    header: 'Name',
    align: 'left',
    sortValue: (r) => r.symbol,
    sortType: 'text',
    render: (r) => (
      <span className="font-medium text-ay-text" title={r.company ?? undefined}>
        {r.symbol}
      </span>
    ),
    mobileLabel: 'Name',
    help: 'The company / equity symbol (hover for the full name).',
  },
  {
    id: 'subject',
    header: 'Subject',
    align: 'left',
    sortValue: (r) => r.subject ?? '',
    sortType: 'text',
    render: (r) => <span className="text-ay-text">{r.subject ?? '—'}</span>,
    mobileLabel: 'Subject',
    help: 'The filing category (Press Release, Board Meeting, Resignation, …).',
  },
  {
    id: 'detail',
    header: 'Detail',
    align: 'left',
    render: (r) => (
      <span className="block max-w-md whitespace-normal break-words text-ay-muted">
        {r.detail ?? '—'}
      </span>
    ),
    mobileLabel: 'Detail',
    help: 'The full announcement text.',
  },
  {
    id: 'attachment',
    header: 'Attachment',
    align: 'center',
    render: (r) =>
      r.fileLink ? (
        <a
          href={r.fileLink}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 text-accent underline-offset-2 hover:underline"
          title="Open the announcement PDF in a new tab"
        >
          <ExternalLink aria-hidden="true" className="size-3.5" />
          Open File
        </a>
      ) : (
        <span className="text-ay-muted">—</span>
      ),
    mobileLabel: 'Attachment',
    help: 'A link to the announcement PDF on the NSE archive (opens in a new tab).',
  },
];

export function AnnouncementPage() {
  const def = useMemo(defaultRange, []);
  const [from, setFrom] = useState(def.from);
  const [to, setTo] = useState(def.to);
  const [symbol, setSymbol] = useState('');
  // The COMMITTED filter — only Go updates it, so the NSE call fires once per query.
  const [applied, setApplied] = useState({ from: def.from, to: def.to, symbol: '' });

  const q = useAnnouncements(applied);

  return (
    <LoadBeat>
      <PageHeader
        title="Announcement"
        subtitle="NSE corporate filings — searchable by symbol and date range, with the full text and the announcement PDF"
        help="The exchange corporate-announcements feed (press releases, board meetings, KMP changes, …). Pick a date range and optionally a symbol, then Go. Each row links to the announcement PDF on the NSE archive."
      />

      <div className="mb-3 flex flex-wrap items-end gap-2">
        <div className="flex flex-col gap-1 text-caption text-ay-muted">
          <span>From</span>
          <DateInput value={from} onChange={(v) => setFrom(v ?? from)} ariaLabel="Start date" title="Range start date" />
        </div>
        <div className="flex flex-col gap-1 text-caption text-ay-muted">
          <span>To</span>
          <DateInput value={to} onChange={(v) => setTo(v ?? to)} ariaLabel="End date" title="Range end date" />
        </div>
        <input
          type="search"
          value={symbol}
          onChange={(e) => setSymbol(e.target.value)}
          placeholder="Symbol (optional)"
          aria-label="Equity symbol"
          title="Filter to one equity symbol; leave blank for all"
          className="h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <GoButton onClick={() => setApplied({ from, to, symbol })} loading={q.isFetching} />
      </div>

      <QueryState
        query={q}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          icon: Megaphone,
          title: 'No announcements for this range.',
          hint: 'Widen the date range, clear the symbol, or — on a mock stack — the NSE announcements source is not wired.',
        }}
        errorTitle="Couldn't load announcements"
        skeleton={<Skeleton variant="table-rows" rows={10} cols={6} />}
      >
        {(rows) => (
          <BeatBlock>
            <DataTable
              columns={COLUMNS}
              rows={rows}
              rowKey={(r) => `${r.symbol}|${r.date}|${r.time}|${r.subject ?? ''}|${r.fileLink ?? ''}`}
              pageSize={10}
              initialSort={{ id: 'date', dir: 'desc' }}
              ariaLabel="Corporate announcements"
              emptyMessage="No announcements."
            />
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

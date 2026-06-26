import { useMemo } from 'react';
import { useHolidays } from '../../api/holidays.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { cn } from '../../lib/cn.ts';
import type { HolidayRow } from '../../api/types.ts';

// Market Holidays (§features/market-holidays — oipulse "Market Holidays"). A sortable, paginated reference
// table of the NSE trading holidays the bundled calendar covers: Date · Day · Description · Validity. The
// Validity badge (Passed / Coming) is derived client-side vs today. Read-only, no controls (the list is
// the bundled calendar; no per-year selector — a documented divergence from oipulse's year view).

/** The IST calendar day as 'YYYY-MM-DD' (timezone-pinned) for the Passed/Coming split. */
function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
}

/** A ring-pill (label is the cue, ring tone supportive) — red Passed (date < today) / green Coming. */
function ValidityBadge({ passed }: { passed: boolean }) {
  return (
    <span
      className={cn(
        'inline-block whitespace-nowrap rounded px-2 py-0.5 text-xs font-semibold ring-1',
        passed ? 'text-bear ring-bear/40' : 'text-bull ring-bull/40',
      )}
    >
      {passed ? 'Passed' : 'Coming'}
    </span>
  );
}

export function MarketHolidaysPage() {
  const q = useHolidays();
  const today = todayIst();
  const rows = q.data ?? [];

  const columns = useMemo<DataColumn<HolidayRow>[]>(
    () => [
      {
        id: 'date',
        header: 'Date',
        align: 'left',
        sortValue: (r) => r.date, // ISO sorts chronologically
        sortType: 'text',
        render: (r) => r.date,
        mobileLabel: 'Date',
        help: 'The calendar date on which the NSE is closed and no trading takes place.',
      },
      {
        id: 'day',
        header: 'Day',
        align: 'left',
        sortValue: (r) => r.day,
        sortType: 'text',
        render: (r) => r.day,
        mobileLabel: 'Day',
        help: 'The day of the week the holiday falls on.',
      },
      {
        id: 'description',
        header: 'Description',
        align: 'left',
        sortValue: (r) => r.description,
        sortType: 'text',
        render: (r) => r.description,
        mobileLabel: 'Description',
        help: 'The name or reason for the holiday (for example a festival or national holiday).',
      },
      {
        id: 'validity',
        header: 'Validity',
        align: 'left',
        sortValue: (r) => (r.date < today ? 'Passed' : 'Coming'),
        sortType: 'text',
        render: (r) => <ValidityBadge passed={r.date < today} />,
        mobileLabel: 'Validity',
        help: 'Whether this holiday has already passed or is still coming up, relative to today.',
      },
    ],
    [today],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="Market Holidays"
        subtitle="NSE trading holidays the bundled calendar covers — Passed / Coming vs today"
        help="Lists the days the NSE is closed for trading, with each marked Passed (already gone by) or Coming (still ahead) relative to today, so you can plan around non-trading sessions."
      />

      <QueryState
        query={q}
        empty={{ title: 'No holidays for the covered years.' }}
        errorTitle="Couldn't load market holidays"
        skeleton={<Skeleton variant="table-rows" rows={10} cols={4} />}
      >
        {() => (
          <BeatBlock>
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(r) => r.date}
              pageSize={20}
              initialSort={{ id: 'date', dir: 'desc' }}
              emptyMessage="No holidays for the covered years."
              ariaLabel="NSE trading holidays"
            />
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

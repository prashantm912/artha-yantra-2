import { useMemo, useState } from 'react';
import { useParticipantOi } from '../../api/oiAnalytics.ts';
import { foldParticipantOi, type ParticipantSegmentRow } from '../../api/participantOiFold.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { SentimentBadge } from '../../components/atoms/SentimentBadge.tsx';
import { SignedCount } from '../../components/atoms/SignedCount.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { useDefaultDate } from '../../api/marketCalendar.ts';

// Participant-wise OI (oipulse §fii-dii/participant-wise-oi): SEBI participant long/short contracts per
// F&O segment, grouped FII/Pro/DII/Client × 6 segments, with day-over-day change + a Bullish/Bearish
// read. The BE /participant-oi feed gives absolute long/short per (date, participant); we fetch a small
// window and diff the latest captured date vs the prior (foldParticipantOi) — no trading-calendar logic.

const isoMinus = (dateStr: string, days: number): string => {
  const d = new Date(`${dateStr}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
};

const num = (n: number) => n.toLocaleString('en-IN');

const COLUMNS: DataColumn<ParticipantSegmentRow>[] = [
  { id: 'segment', header: 'Type', align: 'left', render: (r) => r.segment, mobileLabel: 'Type', help: 'The F&O segment this long/short row covers (e.g. index futures, stock options).' },
  { id: 'long', header: 'Long', render: (r) => `${num(r.long)}${r.longPct ? ` (${r.longPct}%)` : ''}`, mobileLabel: 'Long', help: "Long (buy) contracts this participant holds in the segment; the bracket is this participant's share of ALL participants' longs in the segment." },
  { id: 'short', header: 'Short', render: (r) => `${num(r.short)}${r.shortPct ? ` (${r.shortPct}%)` : ''}`, mobileLabel: 'Short', help: "Short (sell) contracts this participant holds in the segment; the bracket is this participant's share of ALL participants' shorts in the segment." },
  { id: 'totalDiff', header: 'Total Diff.', render: (r) => <SignedCount value={r.totalDiff} />, mobileLabel: 'Total Diff', help: 'Long contracts minus short contracts — positive means net long (bullish lean) in the segment.' },
  { id: 'chngLong', header: 'Chng. In Long', render: (r) => <SignedCount value={r.chngLong} />, help: 'Change in long contracts versus the prior captured session — new longs added (+) or cut (−).' },
  { id: 'chngShort', header: 'Chng. In Short', render: (r) => <SignedCount value={r.chngShort} />, help: 'Change in short contracts versus the prior captured session — new shorts added (+) or cut (−).' },
  { id: 'chngTotal', header: 'Chng. In Total', render: (r) => <SignedCount value={r.chngTotal} />, help: 'Day-over-day change in the net (long minus short) position — the direction of the participant shift.' },
  {
    id: 'interp',
    header: 'Interpretation',
    align: 'center',
    render: (r) => (r.interpretation ? <SentimentBadge label={r.interpretation.label} tone={r.interpretation.tone} /> : <span className="text-ay-muted">—</span>),
    mobileLabel: 'Interpretation',
    help: 'Day-delta read: the sign of Chng-in-Total decides (net position ADDED = bullish), with put segments inverted — puts being added lean bearish.',
  },
];

export function ParticipantWiseOiPage() {
  // Default to the last trading session (#12) so a weekend/holiday open lands on real data.
  const defaultDate = useDefaultDate();
  const [picked, setPicked] = useState<string | null>(null);
  const date = picked ?? defaultDate;
  const from = useMemo(() => isoMinus(date, 10), [date]);
  const q = useParticipantOi(from, date);
  const groups = useMemo(() => foldParticipantOi(q.data ?? []), [q.data]);

  return (
    <LoadBeat>
      <PageHeader
        title="Participant Wise OI (No. of Contracts)"
        help="Shows long vs short F&O contract counts for each SEBI participant group (FII, Pro, DII, Client) per segment, with the day-over-day change and a bullish/bearish read."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <DateInput ariaLabel="Report date" value={date} onChange={setPicked} title="Pick the report date; data falls back to the last trading session." />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} title="Reload participant OI for the selected date." />
      </div>

      <QueryState
        query={q}
        isEmpty={() => groups.length === 0}
        empty={{ title: 'No participant-OI data for this date.' }}
        errorTitle="Couldn't load participant-wise OI"
        skeleton={<Skeleton variant="table-rows" rows={6} cols={8} />}
      >
        {() => (
          <BeatBlock className="space-y-4">
            {groups.map((g) => (
              <div key={g.participant}>
                <div className="mb-1 text-h3 text-accent">{g.participant}</div>
                <DataTable
                  columns={COLUMNS}
                  rows={g.segments}
                  rowKey={(r) => r.segment}
                  ariaLabel={`Participant OI — ${g.participant}`}
                  emptyMessage="No segments."
                />
              </div>
            ))}
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

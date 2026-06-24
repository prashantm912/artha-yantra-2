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

// Participant-wise OI (oipulse §fii-dii/participant-wise-oi): SEBI participant long/short contracts per
// F&O segment, grouped FII/Pro/DII/Client × 6 segments, with day-over-day change + a Bullish/Bearish
// read. The BE /participant-oi feed gives absolute long/short per (date, participant); we fetch a small
// window and diff the latest captured date vs the prior (foldParticipantOi) — no trading-calendar logic.

const todayIso = (): string => new Date().toISOString().slice(0, 10);

const isoMinus = (dateStr: string, days: number): string => {
  const d = new Date(`${dateStr}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
};

const num = (n: number) => n.toLocaleString('en-IN');

const COLUMNS: DataColumn<ParticipantSegmentRow>[] = [
  { id: 'segment', header: 'Type', align: 'left', render: (r) => r.segment, mobileLabel: 'Type' },
  { id: 'long', header: 'Long', render: (r) => `${num(r.long)}${r.longPct ? ` (${r.longPct}%)` : ''}`, mobileLabel: 'Long' },
  { id: 'short', header: 'Short', render: (r) => `${num(r.short)}${r.shortPct ? ` (${r.shortPct}%)` : ''}`, mobileLabel: 'Short' },
  { id: 'totalDiff', header: 'Total Diff.', render: (r) => <SignedCount value={r.totalDiff} />, mobileLabel: 'Total Diff' },
  { id: 'chngLong', header: 'Chng. In Long', render: (r) => <SignedCount value={r.chngLong} /> },
  { id: 'chngShort', header: 'Chng. In Short', render: (r) => <SignedCount value={r.chngShort} /> },
  { id: 'chngTotal', header: 'Chng. In Total', render: (r) => <SignedCount value={r.chngTotal} /> },
  {
    id: 'interp',
    header: 'Interpretation',
    align: 'center',
    render: (r) => (r.interpretation ? <SentimentBadge label={r.interpretation.label} tone={r.interpretation.tone} /> : <span className="text-ay-muted">—</span>),
    mobileLabel: 'Interpretation',
  },
];

export function ParticipantWiseOiPage() {
  const [date, setDate] = useState<string>(todayIso());
  const from = useMemo(() => isoMinus(date, 10), [date]);
  const q = useParticipantOi(from, date);
  const groups = useMemo(() => foldParticipantOi(q.data ?? []), [q.data]);

  return (
    <LoadBeat>
      <PageHeader title="Participant Wise OI (No. of Contracts)" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <DateInput ariaLabel="Report date" value={date} onChange={(v) => v && setDate(v)} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
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

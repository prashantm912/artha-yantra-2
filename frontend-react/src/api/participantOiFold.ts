import type { SentimentTone } from '../components/atoms/SentimentBadge.tsx';
import type { ParticipantOiRow } from './types.ts';

// Participant-wise OI fold (oipulse §fii-dii/participant-wise-oi): the BE /participant-oi feed returns
// one wide row per (date, participant) with ABSOLUTE long/short per F&O segment. oipulse shows the
// change columns from a same-source prior-day diff; we fetch a small date window and diff the latest
// captured date against the prior captured date (no trading-calendar logic — we diff whatever two most
// recent dates are present). Pivoted into 4 participant groups × 6 segment rows.
//   Total Diff.   = Long − Short.   Chng. In Total = ΔLong − ΔShort.
//
// Semantics aligned to the barometer (audit 2026-07-02 §9.2-1/2 — same label, different meaning was
// a decision risk):
//   Long/Short %  = the participant's share of THAT SEGMENT market-wide (long ÷ all participants'
//                   long in the segment) — the standard reading. It was previously the share of the
//                   participant's OWN book, which read "0.6%" where the market convention says "8%".
//   Interpretation = the DAY-DELTA read: sign of Chng-in-Total (ΔLong − ΔShort), with PUT segments
//                   inverted (puts being ADDED lean bearish). It was previously a position-level
//                   read that disagreed with the delta read on identical numbers.

export interface ParticipantSegmentRow {
  segment: string;
  long: number;
  longPct: string | null;
  short: number;
  shortPct: string | null;
  totalDiff: number;
  chngLong: number | null;
  chngShort: number | null;
  chngTotal: number | null;
  interpretation: { label: 'Bullish' | 'Bearish' | 'Neutral'; tone: SentimentTone } | null;
}

export interface ParticipantGroup {
  participant: string;
  segments: ParticipantSegmentRow[];
}

interface SegmentDef {
  label: string;
  long: (r: ParticipantOiRow) => number;
  short: (r: ParticipantOiRow) => number;
}

// The 6 oipulse segments, in display order.
const SEGMENTS: SegmentDef[] = [
  { label: 'Future Index', long: (r) => r.futureIndexLong, short: (r) => r.futureIndexShort },
  { label: 'Future Stock', long: (r) => r.futureStockLong, short: (r) => r.futureStockShort },
  { label: 'Option Index Call', long: (r) => r.optionIndexCallLong, short: (r) => r.optionIndexCallShort },
  { label: 'Option Index Put', long: (r) => r.optionIndexPutLong, short: (r) => r.optionIndexPutShort },
  { label: 'Option Stock Call', long: (r) => r.optionStockCallLong, short: (r) => r.optionStockCallShort },
  { label: 'Option Stock Put', long: (r) => r.optionStockPutLong, short: (r) => r.optionStockPutShort },
];

// FII > Pro > DII > Client (the doc's smart-money importance order).
const PARTICIPANT_ORDER = ['FII', 'Pro', 'DII', 'Client'];

const pctOf = (part: number, total: number): string | null =>
  total === 0 ? null : ((part / total) * 100).toFixed(1);

// Day-delta read with put-inversion (the barometer semantic — see the file header): the sign of
// Chng-in-Total decides; put segments invert (puts ADDED = bearish). Null without a prior date.
function interpret(
  segment: string,
  chngTotal: number | null,
): ParticipantSegmentRow['interpretation'] {
  if (chngTotal == null) return null;
  if (chngTotal === 0) return { label: 'Neutral', tone: 'neutral' };
  const bullish = segment.includes('Put') ? chngTotal < 0 : chngTotal > 0;
  return bullish ? { label: 'Bullish', tone: 'bull' } : { label: 'Bearish', tone: 'bear' };
}

/** Pivots the windowed participant rows into 4 groups × 6 segments, diffing latest vs prior date. */
export function foldParticipantOi(rows: ParticipantOiRow[]): ParticipantGroup[] {
  if (rows.length === 0) return [];
  const dates = [...new Set(rows.map((r) => r.tradeDate))].sort((a, b) => b.localeCompare(a));
  const latest = dates[0];
  const prior = dates[1] ?? null;

  const at = (date: string, participant: string) =>
    rows.find((r) => r.tradeDate === date && r.clientType.toUpperCase() === participant.toUpperCase());

  const participants = [...new Set(rows.filter((r) => r.tradeDate === latest).map((r) => r.clientType))];
  participants.sort((a, b) => {
    const ia = PARTICIPANT_ORDER.indexOf(a);
    const ib = PARTICIPANT_ORDER.indexOf(b);
    return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
  });

  // Market-wide denominators per segment (the standard % reading): every participant's long/short
  // summed for the latest date. One pass, keyed by segment label.
  const latestRows = rows.filter((r) => r.tradeDate === latest);
  const segmentTotals = new Map<string, { long: number; short: number }>(
    SEGMENTS.map((s) => [
      s.label,
      {
        long: latestRows.reduce((sum, r) => sum + s.long(r), 0),
        short: latestRows.reduce((sum, r) => sum + s.short(r), 0),
      },
    ]),
  );

  return participants
    .map((participant) => {
      const cur = at(latest, participant);
      if (!cur) return null;
      const prev = prior ? at(prior, participant) : undefined;
      const segments: ParticipantSegmentRow[] = SEGMENTS.map((s) => {
        const long = s.long(cur);
        const short = s.short(cur);
        const chngLong = prev ? long - s.long(prev) : null;
        const chngShort = prev ? short - s.short(prev) : null;
        const chngTotal = chngLong != null && chngShort != null ? chngLong - chngShort : null;
        const totalDiff = long - short;
        const totals = segmentTotals.get(s.label) ?? { long: 0, short: 0 };
        return {
          segment: s.label,
          long,
          longPct: pctOf(long, totals.long),
          short,
          shortPct: pctOf(short, totals.short),
          totalDiff,
          chngLong,
          chngShort,
          chngTotal,
          interpretation: interpret(s.label, chngTotal),
        };
      });
      return { participant, segments };
    })
    .filter((g): g is ParticipantGroup => g !== null);
}

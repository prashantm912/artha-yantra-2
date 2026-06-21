import type { ActiveStrikeIvPoint, ActiveStrikeOiPoint, SentimentPoint } from './types.ts';

// Fold for the oipulse "Active Strikes OI" page (§options/active-strikes). The endpoint (with buckets=N)
// returns two per-bucket arrays built from the SAME server-side fold + the same active-strike selection,
// so they share a bucket axis. This merges them by bucket into the two charts' parallel arrays:
//   LEFT  "Active Strike Change in OI" — Call OI (green) + Put OI (red) lines.
//   RIGHT "Active Strike Sentiment %"  — sentiment % (blue) line (decimal string → number, null-safe).
// Merged on a bucket key (not index) so the axes stay aligned even if one series is shorter.

export interface ActiveStrikeSeries {
  /** Shared x-axis, "HH:mm", oldest-first. */
  times: string[];
  callOi: (number | null)[];
  putOi: (number | null)[];
  sentiment: (number | null)[];
}

const hhmm = (iso: string): string => {
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m ? m[1] : iso;
};

export function foldActiveStrikeSeries(
  oi: ActiveStrikeOiPoint[] | null | undefined,
  sentiment: SentimentPoint[] | null | undefined,
): ActiveStrikeSeries {
  const oiBy = new Map((oi ?? []).map((p) => [p.bucket, p]));
  const sentBy = new Map((sentiment ?? []).map((p) => [p.bucket, p]));
  const buckets = [...new Set([...oiBy.keys(), ...sentBy.keys()])].sort(); // ISO sorts oldest-first

  return {
    times: buckets.map(hhmm),
    callOi: buckets.map((b) => oiBy.get(b)?.ceOi ?? null),
    putOi: buckets.map((b) => oiBy.get(b)?.peOi ?? null),
    sentiment: buckets.map((b) => {
      const s = sentBy.get(b)?.sentimentPct;
      return s == null ? null : Number(s);
    }),
  };
}

export interface ActiveStrikeIvViz {
  /** "HH:mm", oldest-first. */
  times: string[];
  callIv: (number | null)[];
  putIv: (number | null)[];
  price: (number | null)[];
}

/**
 * Fold the active-strike IV series (single peak strike's CE/PE IV + price per bucket) into the dual-axis
 * chart's parallel arrays. Decimal strings cross to number ONLY here (the ECharts coordinate boundary);
 * nulls (an absent IV leg) ride through so the line gaps rather than plots a zero.
 */
export function foldActiveStrikeIvSeries(
  iv: ActiveStrikeIvPoint[] | null | undefined,
): ActiveStrikeIvViz {
  const points = iv ?? [];
  const num = (s: string | null | undefined): number | null => (s == null ? null : Number(s));
  return {
    times: points.map((p) => hhmm(p.bucket)),
    callIv: points.map((p) => num(p.ceIv)),
    putIv: points.map((p) => num(p.peIv)),
    price: points.map((p) => num(p.price)),
  };
}

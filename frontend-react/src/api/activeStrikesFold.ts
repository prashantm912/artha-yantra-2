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
 * nulls (an absent IV leg) ride through so the line gaps rather than plots a zero. IVs are solver-scale
 * decimals (0.106) on the wire and plot in PERCENT (10.6) — the barometer's axis convention (§10.2-1).
 */
export function foldActiveStrikeIvSeries(
  iv: ActiveStrikeIvPoint[] | null | undefined,
): ActiveStrikeIvViz {
  const points = iv ?? [];
  const num = (s: string | null | undefined): number | null => (s == null ? null : Number(s));
  // ×100 with a 4dp round so binary-float dust (0.1064 → 10.639999…) never reaches the tooltip
  const pct = (s: string | null | undefined): number | null =>
    s == null ? null : Number((Number(s) * 100).toFixed(4));
  return {
    times: points.map((p) => hhmm(p.bucket)),
    callIv: points.map((p) => pct(p.ceIv)),
    putIv: points.map((p) => pct(p.peIv)),
    price: points.map((p) => num(p.price)),
  };
}

/** Decimal-vol string → display percent with one decimal ("0.1064" → "10.6"); null-safe. */
export function ivPct(s: string | null | undefined): string | null {
  return s == null ? null : (Number(s) * 100).toFixed(1);
}

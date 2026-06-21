import type { MultiOi } from '../api/types.ts';

// Multiple OI Chart series maths (§options/multiple-oi-chart). Folds the per-leg OI lines + the shared
// underlying-price line into the ECharts plotting arrays, merging on a bucket KEY (not index) so the
// axes stay aligned even if a leg's series is shorter. The price (spot) decimal STRING crosses to number
// ONLY here (the ECharts coordinate boundary); OI is already a number; nulls ride through (connectNulls
// gaps them rather than plotting a zero).

export interface MultiLegSeries {
  /** Shared x-axis, "HH:mm", oldest-first. */
  times: string[];
  /** One OI line per selected leg (right axis). */
  legs: { label: string; oi: (number | null)[] }[];
  /** The underlying price (left axis). */
  price: (number | null)[];
}

const hhmm = (iso: string): string => {
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m ? m[1] : iso;
};

export function toMultiLegSeries(data: MultiOi | null | undefined): MultiLegSeries {
  if (!data) {
    return { times: [], legs: [], price: [] };
  }
  // Union of every leg's + the spot's buckets (ISO sorts oldest-first).
  const set = new Set<string>();
  for (const l of data.items) for (const p of l.points) set.add(p.bucket);
  for (const s of data.spot) set.add(s.bucket);
  const buckets = [...set].sort();

  const spotBy = new Map(data.spot.map((s) => [s.bucket, s.spot]));
  const legs = data.items.map((l) => {
    const by = new Map(l.points.map((p) => [p.bucket, p.oi]));
    return { label: l.leg, oi: buckets.map((b) => by.get(b) ?? null) };
  });
  const price = buckets.map((b) => {
    const s = spotBy.get(b);
    return s == null ? null : Number(s);
  });

  return { times: buckets.map(hhmm), legs, price };
}

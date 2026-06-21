import type { OiStrikePoint } from '../api/types.ts';

// Fold for the oipulse "Options OI Chart" (§options/oi-chart) — the chart counterpart of Options OI
// Analysis, off the SAME strike-series feed (one strike's CE+PE points per session bucket). Three line
// charts ride these arrays: Call-vs-Put OI; Call OI vs Call premium; Put OI vs Put premium. OI is a
// number; the premium (ltp) decimal STRING crosses to number ONLY here (the ECharts coordinate boundary);
// nulls ride through so a line gaps rather than plotting a zero.

export interface OptionsOiChartViz {
  /** Shared x-axis, "HH:mm", oldest-first. */
  times: string[];
  callOi: (number | null)[];
  putOi: (number | null)[];
  /** The Call/Put premium (option ltp/close) per bucket. */
  callPrice: (number | null)[];
  putPrice: (number | null)[];
}

const hhmm = (iso: string): string => {
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m ? m[1] : iso;
};

const num = (s: string | null | undefined): number | null => (s == null ? null : Number(s));

/** Groups the flat CE/PE strike-series points by bucket (oldest-first) and splits into the chart arrays. */
export function foldOptionsOiChart(items: OiStrikePoint[]): OptionsOiChartViz {
  const byBucket = new Map<string, { ce: OiStrikePoint | null; pe: OiStrikePoint | null }>();
  for (const p of items) {
    const e = byBucket.get(p.bucket) ?? { ce: null, pe: null };
    if (p.optionType === 'CE') e.ce = p;
    else e.pe = p;
    byBucket.set(p.bucket, e);
  }
  const buckets = [...byBucket.keys()].sort(); // ISO sorts oldest-first

  return {
    times: buckets.map(hhmm),
    callOi: buckets.map((b) => byBucket.get(b)!.ce?.oi ?? null),
    putOi: buckets.map((b) => byBucket.get(b)!.pe?.oi ?? null),
    callPrice: buckets.map((b) => num(byBucket.get(b)!.ce?.ltp)),
    putPrice: buckets.map((b) => num(byBucket.get(b)!.pe?.ltp)),
  };
}

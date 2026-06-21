import { compareDecimal, isNegative, subtractDecimal } from '../lib/decimal.ts';
import { classifyOi, type OiInterpretation } from '../core/oiInterpretation.ts';
import type { OiStrikePoint } from './types.ts';

// Folds the strike-series (one strike's CE+PE points per session bucket) into the oipulse "Options OI
// Analysis" time-rows (§20.7.5). Per side per bucket it derives: the interval ΔOI / Δltp (vs the prior
// bucket), the cumulative ΔOI (from the session's first bucket), the 4-state interpretation, and the
// day-high / day-low break (a new running extreme of the option's ltp). Decimal strings — never parseFloat.

export interface OiAnalysisSide {
  oi: number | null;
  oiChange: number | null;
  cumOiChange: number | null;
  ltp: string | null;
  ltpChange: string | null;
  interpretation: OiInterpretation | null;
  dhBreak: boolean;
  dlBreak: boolean;
}

export interface OiAnalysisRow {
  bucket: string;
  ce: OiAnalysisSide;
  pe: OiAnalysisSide;
}

const EMPTY_SIDE: OiAnalysisSide = {
  oi: null,
  oiChange: null,
  cumOiChange: null,
  ltp: null,
  ltpChange: null,
  interpretation: null,
  dhBreak: false,
  dlBreak: false,
};

/** Derive one side's per-bucket row, oldest-first; deltas are vs the prior non-null bucket. */
function foldSide(points: (OiStrikePoint | null)[]): OiAnalysisSide[] {
  const out: OiAnalysisSide[] = [];
  let firstOi: number | null = null;
  let runHigh: string | null = null;
  let runLow: string | null = null;
  let prev: OiStrikePoint | null = null;

  for (const p of points) {
    if (p == null) {
      out.push(EMPTY_SIDE);
      continue;
    }
    if (firstOi == null && p.oi != null) firstOi = p.oi;

    const oiChange = p.oi != null && prev?.oi != null ? p.oi - prev.oi : null;
    const ltpChange =
      p.ltp != null && prev?.ltp != null ? subtractDecimal(p.ltp, prev.ltp) : null;
    const cumOiChange = p.oi != null && firstOi != null ? p.oi - firstOi : null;

    const interpretation =
      ltpChange != null && oiChange != null
        ? classifyOi(!isNegative(ltpChange), oiChange >= 0)
        : null;

    let dhBreak = false;
    let dlBreak = false;
    if (p.ltp != null) {
      if (runHigh != null && compareDecimal(p.ltp, runHigh) > 0) dhBreak = true;
      if (runLow != null && compareDecimal(p.ltp, runLow) < 0) dlBreak = true;
      if (runHigh == null || compareDecimal(p.ltp, runHigh) > 0) runHigh = p.ltp;
      if (runLow == null || compareDecimal(p.ltp, runLow) < 0) runLow = p.ltp;
    }

    out.push({
      oi: p.oi,
      oiChange,
      cumOiChange,
      ltp: p.ltp,
      ltpChange,
      interpretation,
      dhBreak,
      dlBreak,
    });
    prev = p;
  }
  return out;
}

/** Group the flat CE/PE points by bucket (oldest-first) and fold each side. */
export function foldOiAnalysis(items: OiStrikePoint[]): OiAnalysisRow[] {
  const byBucket = new Map<string, { ce: OiStrikePoint | null; pe: OiStrikePoint | null }>();
  for (const p of items) {
    const e = byBucket.get(p.bucket) ?? { ce: null, pe: null };
    if (p.optionType === 'CE') e.ce = p;
    else e.pe = p;
    byBucket.set(p.bucket, e);
  }
  const buckets = [...byBucket.keys()].sort(); // ISO timestamps sort oldest-first lexically
  const ceSides = foldSide(buckets.map((b) => byBucket.get(b)!.ce));
  const peSides = foldSide(buckets.map((b) => byBucket.get(b)!.pe));
  return buckets.map((b, i) => ({ bucket: b, ce: ceSides[i], pe: peSides[i] }));
}

/** "HH:mm" from an ISO bucket timestamp (display label for the Time column). */
export function bucketTime(iso: string): string {
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m ? m[1] : iso;
}

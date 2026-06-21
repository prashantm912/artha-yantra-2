import { classifyOi, type OiInterpretation } from '../core/oiInterpretation.ts';
import { compareDecimal, isNegative, subtractDecimal } from '../lib/decimal.ts';
import type { FutSeriesPoint } from './types.ts';

// Folds one futures contract's raw per-bucket points (the BE oi-analysis-series feed) into the oipulse
// "Futures OI Analysis" per-interval table (§futures/oi-analysis). Single-contract sibling of the options
// oiAnalysisFold. Decimal strings — never parseFloat. Per bucket it derives, vs the prior bucket:
//   Total Chng. In OI = OI − the session's FIRST captured OI (the day-open baseline).
//   LTP Change / OI Change = vs the immediately-prior bucket (first row null).
//   Volume = the per-interval traded volume = cumulative day-volume delta (reset-guarded).
//   Level Break  = a new session extreme — fires when the CAPTURED day_high/day_low advances this bucket
//                  (futures carries the real session extremes, V015 — unlike the options page which only
//                  has leg LTP, so this is faithful, not a close-based proxy). breakLevel = the new extreme.
//   OI Interpretation = classifyOi(price↑, oi↑) — the shared 4-state primitive.
//
// DIVERGENCE (documented): the day-open OI baseline is the session's FIRST CAPTURED bucket OI, not NSE's
// prior-close-carried day-open OI (forward-capture from boot). On a declining-OI session the cumulative
// Total-Chng-In-OI can therefore differ in SIGN, not just magnitude, from oipulse's. The 15:30-EOD/PEOD
// adjusted-OI row (a separate NSE clearing source) is not captured and is intentionally absent.

export interface FuturesOiAnalysisRow {
  bucket: string;
  totalOi: number | null;
  totalChngInOi: number | null;
  dayHigh: string | null;
  dayLow: string | null;
  dhBreak: boolean;
  dlBreak: boolean;
  /** The new session extreme this bucket broke (the level oipulse shows in the badge). */
  breakLevel: string | null;
  volume: number | null;
  ltp: string | null;
  ltpChange: string | null;
  oiChange: number | null;
  interpretation: OiInterpretation | null;
}

/** Folds one contract's points (any order) into the per-interval table, oldest-first. */
export function foldFuturesOiAnalysis(items: FutSeriesPoint[]): FuturesOiAnalysisRow[] {
  const asc = [...items].sort((a, b) => a.bucket.localeCompare(b.bucket)); // ISO sorts oldest-first
  let firstOi: number | null = null;
  let runHigh: string | null = null; // running session high across captured day_high values
  let runLow: string | null = null;
  let prev: FutSeriesPoint | null = null;
  const out: FuturesOiAnalysisRow[] = [];

  for (const p of asc) {
    if (firstOi == null && p.oi != null) firstOi = p.oi;

    const oiChange = p.oi != null && prev?.oi != null ? p.oi - prev.oi : null;
    const ltpChange =
      p.ltp != null && prev?.ltp != null ? subtractDecimal(p.ltp, prev.ltp) : null;
    const totalChngInOi = p.oi != null && firstOi != null ? p.oi - firstOi : null;

    // Per-interval volume = cumulative day-volume delta. First bucket / a cumulative DROP (a session or
    // contract-roll reset) → null, never a negative — symmetric with the other first-row-null deltas.
    const volume =
      p.volume != null && prev?.volume != null && p.volume >= prev.volume
        ? p.volume - prev.volume
        : null;

    const interpretation =
      ltpChange != null && oiChange != null
        ? classifyOi(!isNegative(ltpChange), oiChange >= 0)
        : null;

    // Level Break off the REAL captured session extremes: a break fires when this bucket's day_high/day_low
    // pushes past the prior running extreme (the interval that made the new session high/low).
    let dhBreak = false;
    let dlBreak = false;
    let breakLevel: string | null = null;
    if (p.dayHigh != null && runHigh != null && compareDecimal(p.dayHigh, runHigh) > 0) {
      dhBreak = true;
      breakLevel = p.dayHigh;
    }
    if (p.dayLow != null && runLow != null && compareDecimal(p.dayLow, runLow) < 0) {
      dlBreak = true;
      if (!dhBreak) breakLevel = p.dayLow; // a high-break wins the badge → keep its level
    }
    if (p.dayHigh != null && (runHigh == null || compareDecimal(p.dayHigh, runHigh) > 0)) {
      runHigh = p.dayHigh;
    }
    if (p.dayLow != null && (runLow == null || compareDecimal(p.dayLow, runLow) < 0)) {
      runLow = p.dayLow;
    }

    out.push({
      bucket: p.bucket,
      totalOi: p.oi,
      totalChngInOi,
      dayHigh: p.dayHigh,
      dayLow: p.dayLow,
      dhBreak,
      dlBreak,
      breakLevel,
      volume,
      ltp: p.ltp,
      ltpChange,
      oiChange,
      interpretation,
    });
    prev = p;
  }
  return out; // oldest-first; the table sorts newest-first
}

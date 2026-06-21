import { nearestStrike } from '../lib/strikes.ts';
import type { OiStrikePoint, TrendPoint } from './types.ts';

// Folds for the oipulse "Options OI Statistics" page (§options/oi-statistics). Three views, all derived
// client-side from feeds already on the wire:
//   • Cumulative OI  — total CE vs PE OI (from /oi-stats; this file only folds the per-strike profile).
//   • Individual OI  — per-strike CE vs PE OI bars + the ATM strike (from /oi-analysis latest-bucket rows).
//   • PCR vs price   — intraday PCR (peOi/ceOi) against the underlying (from the /trending series).
// "Show Chg. in OI" toggles absolute OI vs interval ΔOI on the bar charts (the page passes useChange).
// OI counts are integers; only the chart COORDINATES (price axis, PCR) cross to numbers — never money maths.

export interface IndividualOi {
  /** Strike ladder, ascending numeric (the bar-chart x axis). */
  strikes: string[];
  /** CE OI (or ΔOI) per strike, index-aligned with {@link strikes}. */
  ce: number[];
  /** PE OI (or ΔOI) per strike. */
  pe: number[];
  /** The listed strike nearest spot (ATM double-arrow marker), or null when spot is absent. */
  atm: string | null;
}

/**
 * Group the latest-bucket per-strike points into ascending-strike CE/PE bars. `useChange` switches the
 * bar value from absolute `oi` to interval `oiChange` (the "Show Chg. in OI" toggle). A missing side is 0
 * (a strike listed for only one type still occupies an x slot — oipulse keeps the ladder contiguous).
 */
export function foldIndividualOi(items: OiStrikePoint[], useChange: boolean): IndividualOi {
  const val = (p: OiStrikePoint): number => (useChange ? (p.oiChange ?? 0) : (p.oi ?? 0));
  const byStrike = new Map<string, { ce: number; pe: number }>();
  let spot: string | null = null;
  for (const p of items) {
    if (spot == null && p.spot != null) spot = p.spot;
    const e = byStrike.get(p.strike) ?? { ce: 0, pe: 0 };
    if (p.optionType === 'CE') e.ce = val(p);
    else e.pe = val(p);
    byStrike.set(p.strike, e);
  }
  // Numeric strike order — the ladder is the x axis (lexical sort would mis-order 5100 vs 57100).
  const strikes = [...byStrike.keys()].sort((a, b) => Number(a) - Number(b));
  return {
    strikes,
    ce: strikes.map((s) => byStrike.get(s)!.ce),
    pe: strikes.map((s) => byStrike.get(s)!.pe),
    atm: nearestStrike(strikes, spot),
  };
}

export interface PcrPricePoint {
  /** "HH:mm" bucket label. */
  time: string;
  /** peOi / ceOi for the bucket, 2dp; null when ceOi is 0. */
  pcr: number | null;
  /** Underlying level (spot) for the bucket; null when absent. */
  price: number | null;
}

/** Fold the /trending per-bucket OI series into the dual-axis PCR-vs-price line (oldest-first). */
export function foldPcrPrice(items: TrendPoint[]): PcrPricePoint[] {
  return items.map((p) => ({
    time: bucketHhmm(p.bucket),
    pcr: p.ceOi === 0 ? null : Math.round((p.peOi / p.ceOi) * 100) / 100,
    price: p.spot == null ? null : Number(p.spot),
  }));
}

/** "HH:mm" from an ISO bucket timestamp. */
export function bucketHhmm(iso: string): string {
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m ? m[1] : iso;
}

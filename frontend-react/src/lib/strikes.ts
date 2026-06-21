import { compareDecimal, isNegative, subtractDecimal } from './decimal.ts';

// Shared strike helpers (extracted from the option pages — Chain / OI Analysis / Straddle all need the
// ATM band). Exact-decimal distance, never parseFloat.

/** The listed strike nearest the spot (the ATM band); falls back to the first strike when spot is null. */
export function nearestStrike(strikes: string[], spot: string | null): string | null {
  if (!spot) return strikes[0] ?? null;
  let best: string | null = null;
  let bestDist: string | null = null;
  for (const s of strikes) {
    const diff = subtractDecimal(s, spot);
    const dist = isNegative(diff) ? diff.slice(1) : diff;
    if (bestDist == null || compareDecimal(dist, bestDist) < 0) {
      bestDist = dist;
      best = s;
    }
  }
  return best;
}

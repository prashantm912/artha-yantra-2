import { compareDecimal, isNegative } from '../lib/decimal.ts';
import type { SpurtRow } from '../api/types.ts';

// oipulse OI-Spurt signal-strength gate: a strike only qualifies as a signal when BOTH the |%ΔLTP|
// and the |%ΔOI| exceed 50 (the inLtpChangeInPercentage / inOiChangeInPercentage decision metrics).
// Kept in a plain .ts (no JSX) so the table component file exports only a component (react-refresh).

const abs = (s: string) => (isNegative(s) ? s.slice(1) : s);

export function isStrong(r: SpurtRow): boolean {
  if (!r.ltpChangePct || !r.spurtPct) return false;
  return compareDecimal(abs(r.ltpChangePct), '50') > 0 && compareDecimal(abs(r.spurtPct), '50') > 0;
}

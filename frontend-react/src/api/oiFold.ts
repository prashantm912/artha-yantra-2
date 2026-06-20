import { compareDecimal } from '../lib/decimal.ts';
import type { LegCell, OiChainRow, OiStrikePoint } from './types.ts';

/**
 * Folds flat per-leg points into one CE/PE row per strike, sorted ascending by strike (decimal-exact).
 * Ported verbatim from frontend-ui oi-analytics.store.ts.
 */
export function foldStrikes(points: OiStrikePoint[]): OiChainRow[] {
  const byStrike = new Map<string, OiChainRow>();
  for (const p of points) {
    let row = byStrike.get(p.strike);
    if (!row) {
      row = { strike: p.strike, ce: null, pe: null, spot: p.spot };
      byStrike.set(p.strike, row);
    }
    const cell: LegCell = { oi: p.oi, oiChange: p.oiChange, iv: p.iv, ltp: p.ltp };
    if (p.optionType === 'CE') row.ce = cell;
    else row.pe = cell;
    if (p.spot !== null && p.spot !== undefined) row.spot = p.spot;
  }
  return [...byStrike.values()].sort((a, b) => compareDecimal(a.strike, b.strike));
}

/** Largest leg OI across the chain (bar scaling); reduce (not spread) — a wide chain overflows args. */
export function maxOptionOi(points: OiStrikePoint[]): number {
  return points.reduce((m, p) => Math.max(m, p.oi ?? 0), 1);
}

/** The underlying spot carried on the chain points (first non-null). */
export function chainSpot(points: OiStrikePoint[]): string | null {
  return points.find((p) => p.spot != null)?.spot ?? null;
}

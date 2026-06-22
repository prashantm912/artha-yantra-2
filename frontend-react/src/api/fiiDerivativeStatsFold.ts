import type { FiiDerivativeRow } from './types.ts';

// FII Derivative Stats fold (oipulse §fii-dii/fii-derivative-stats): the BE /derivative-stats feed
// returns one row per (date, segment); the page pivots them to one row per date with the four F&O
// segment NET values (₹ Crore decimal strings). Newest first (matches the oipulse table).

export interface FiiDerivativeStatsRow {
  tradeDate: string;
  idxFut: string | null; // INDEX_FUTURES net
  idxOpt: string | null; // INDEX_OPTIONS net
  stkFut: string | null; // STOCK_FUTURES net
  stkOpt: string | null; // STOCK_OPTIONS net
}

/** Pivots the per-segment rows into one row per date (newest first). */
export function foldFiiDerivativeStats(rows: FiiDerivativeRow[]): FiiDerivativeStatsRow[] {
  const byDate = new Map<string, FiiDerivativeStatsRow>();
  for (const r of rows) {
    const slot =
      byDate.get(r.tradeDate) ??
      ({ tradeDate: r.tradeDate, idxFut: null, idxOpt: null, stkFut: null, stkOpt: null } as FiiDerivativeStatsRow);
    switch (r.segment) {
      case 'INDEX_FUTURES':
        slot.idxFut = r.netValue;
        break;
      case 'INDEX_OPTIONS':
        slot.idxOpt = r.netValue;
        break;
      case 'STOCK_FUTURES':
        slot.stkFut = r.netValue;
        break;
      case 'STOCK_OPTIONS':
        slot.stkOpt = r.netValue;
        break;
      default:
        break;
    }
    byDate.set(r.tradeDate, slot);
  }
  return [...byDate.values()].sort((a, b) => b.tradeDate.localeCompare(a.tradeDate));
}

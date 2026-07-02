import { classifyOi, type OiInterpretation } from '../core/oiInterpretation.ts';
import { isNegative, subtractDecimal } from '../lib/decimal.ts';
import type { FutEodRow } from './types.ts';

// Futures EOD OI Analyzer fold (oipulse §futures/eod-oi-analyzer): day-over-day columns derived from
// consecutive daily rows (the BE /eod feed gives raw per-IST-day OHLC + OI). The displayed price deltas
// use exact-decimal subtraction; the % ratios are derived (Number → fixed) — never a price comparison.
//   Day Range   = Day High − Day Low (+ range/close %).
//   LTP Change  = close − prior-day close;   OI Change = OI − prior-day OI (day-over-day, NOT the row's
//                 intraday oiChange sum).   OI Interpretation = classifyOi(price↑, oi↑).
// Newest date first (oipulse table is descending).

export interface FuturesEodRow {
  tradeDate: string;
  totalOi: number;
  dayOpen: string | null;
  dayHigh: string | null;
  dayLow: string | null;
  volume: number;
  ltp: string | null;
  dayRange: string | null;
  dayRangePct: string | null;
  ltpChange: string | null;
  ltpChangePct: string | null;
  oiChange: number | null;
  oiChangePct: string | null;
  interpretation: OiInterpretation | null;
}

const FUT_MONTHS: Record<string, number> = {
  JAN: 1, FEB: 2, MAR: 3, APR: 4, MAY: 5, JUN: 6, JUL: 7, AUG: 8, SEP: 9, OCT: 10, NOV: 11, DEC: 12,
};

/** Expiry rank of a dated FUT symbol ("NIFTY26JULFUT" → 26·12+7); unparseable sorts last. */
function futExpiryRank(tradingsymbol: string): number {
  const m = /(\d{2})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)FUT$/.exec(tradingsymbol);
  return m ? Number(m[1]) * 12 + FUT_MONTHS[m[2]] : Number.MAX_SAFE_INTEGER;
}

/**
 * Contract list for the selector, FRONT MONTH FIRST (audit 2026-07-02 §10.2-4: the old
 * most-captured-rows heuristic defaulted to a far month). Contracts still trading (rows on the
 * latest captured session) rank before expired ones; within each group the nearest expiry wins.
 */
export function orderContractsFrontFirst(rows: FutEodRow[]): string[] {
  if (rows.length === 0) return [];
  const latest = rows.reduce((max, r) => (r.tradeDate > max ? r.tradeDate : max), '');
  const alive = new Set(rows.filter((r) => r.tradeDate === latest).map((r) => r.tradingsymbol));
  return [...new Set(rows.map((r) => r.tradingsymbol))].sort(
    (a, b) =>
      Number(alive.has(b)) - Number(alive.has(a)) || futExpiryRank(a) - futExpiryRank(b),
  );
}

/** Signed change % of (curr − base) / base — a derived ratio (not a displayed/compared price). */
function changePct(curr: string, base: string): string | null {
  const b = Number(base);
  if (!Number.isFinite(b) || b === 0) return null;
  return (((Number(curr) - b) / b) * 100).toFixed(2);
}

/** Range as a % of the day close (oipulse renders "620 (1.09 %)"). */
function rangePctOf(range: string, close: string): string | null {
  const c = Number(close);
  if (!Number.isFinite(c) || c === 0) return null;
  return ((Number(range) / c) * 100).toFixed(2);
}

/**
 * The CONTINUOUS current-month stitch (audit §10.2-4, Wave 5): one row per session, taken from that
 * session's FRONT contract. A session's front = the lowest-expiry contract that produced rows that
 * day — an expired contract stops producing snapshots, so the stitch rolls automatically on the
 * session after expiry. Day-over-day deltas run ACROSS rolls (the roll session's OI/LTP change spans
 * two contracts — flagged via `rolledFrom` so the page can mark it).
 */
export function stitchContinuousFrontMonth(
  rows: FutEodRow[],
): { rows: FuturesEodRow[]; contractByDate: Map<string, string> } {
  const byDate = new Map<string, FutEodRow>();
  for (const r of rows) {
    const cur = byDate.get(r.tradeDate);
    if (!cur || futExpiryRank(r.tradingsymbol) < futExpiryRank(cur.tradingsymbol)) {
      byDate.set(r.tradeDate, r);
    }
  }
  const stitched = [...byDate.values()];
  const contractByDate = new Map(stitched.map((r) => [r.tradeDate, r.tradingsymbol]));
  return { rows: foldFuturesEod(stitched), contractByDate };
}

/**
 * Folds one contract's daily rows into the EOD table (newest-first). {@code rows} may be in any order;
 * the day-over-day deltas are computed against the chronologically prior day.
 */
export function foldFuturesEod(rows: FutEodRow[]): FuturesEodRow[] {
  const asc = [...rows].sort((a, b) => a.tradeDate.localeCompare(b.tradeDate));
  const out: FuturesEodRow[] = asc.map((r, i) => {
    const prev = i > 0 ? asc[i - 1] : null;
    const range = r.high != null && r.low != null ? subtractDecimal(r.high, r.low) : null;
    const rangePct = range != null && r.close != null ? rangePctOf(range, r.close) : null;

    const ltpChange =
      prev?.close != null && r.close != null ? subtractDecimal(r.close, prev.close) : null;
    const ltpChangePct =
      prev?.close != null && r.close != null ? changePct(r.close, prev.close) : null;
    const oiChange = prev != null ? r.oiClose - prev.oiClose : null;
    const oiChangePct =
      prev != null && prev.oiClose !== 0
        ? (((r.oiClose - prev.oiClose) / prev.oiClose) * 100).toFixed(2)
        : null;

    const interpretation =
      ltpChange != null && oiChange != null
        ? classifyOi(!isNegative(ltpChange), oiChange >= 0)
        : null;

    return {
      tradeDate: r.tradeDate,
      totalOi: r.oiClose,
      dayOpen: r.open,
      dayHigh: r.high,
      dayLow: r.low,
      volume: r.volume,
      ltp: r.close,
      dayRange: range,
      // oipulse renders "620 (1.09 %)" — keep range + its % as separate fields for the cell.
      dayRangePct: rangePct,
      ltpChange,
      ltpChangePct,
      oiChange,
      oiChangePct,
      interpretation,
    };
  });
  return out.reverse(); // newest date first
}

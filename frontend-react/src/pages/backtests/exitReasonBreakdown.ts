import { addDecimal, isNegative } from '../../lib/decimal.ts';
import type { TradeRow } from '../../api/backtests.ts';

// Client-side exit-reason forensics for the Results/Trades tab: aggregate the loaded trades by their
// exitReason into count / total-P&L / win-rate / avg-P&L. Pure + exact-decimal (never parseFloat) so it
// can be unit-tested in isolation and reused without dragging the page component into the test.

/** One exit-reason bucket: count, summed P&L (exact-decimal), wins, derived win-rate + avg P&L. */
export interface ExitReasonStat {
  reason: string;
  count: number;
  wins: number;
  totalPnl: string;
  winRate: number;
  avgPnl: string;
}

/**
 * Aggregate the loaded trades by exit reason — count, total P&L (string-exact), win-rate and avg P&L.
 * Sorted by count desc (ties broken by reason) so the dominant exit leads.
 */
export function exitReasonBreakdown(trades: TradeRow[]): ExitReasonStat[] {
  const acc = new Map<string, { count: number; wins: number; totalPnl: string }>();
  for (const t of trades) {
    const reason = t.exitReason || '—';
    const cur = acc.get(reason) ?? { count: 0, wins: 0, totalPnl: '0' };
    cur.count += 1;
    if (!isNegative(t.pnl)) cur.wins += 1;
    cur.totalPnl = addDecimal(cur.totalPnl, t.pnl);
    acc.set(reason, cur);
  }
  return [...acc.entries()]
    .map(([reason, v]) => ({
      reason,
      count: v.count,
      wins: v.wins,
      totalPnl: v.totalPnl,
      winRate: (v.wins / v.count) * 100,
      avgPnl: divideDecimal(v.totalPnl, v.count),
    }))
    .sort((a, b) => b.count - a.count || a.reason.localeCompare(b.reason));
}

/** Exact decimal ÷ positive integer to 2 dp (string in, string out) — never parseFloat. */
function divideDecimal(value: string, n: number): string {
  const neg = isNegative(value);
  const v = neg ? value.slice(1) : value;
  const [intRaw, fracRaw = ''] = v.split('.');
  const scaled = BigInt((intRaw || '0') + fracRaw.padEnd(4, '0').slice(0, 4)); // ×10^4
  const q = scaled / BigInt(n);
  const digits = q.toString().padStart(5, '0');
  const body = `${digits.slice(0, digits.length - 4)}.${digits.slice(digits.length - 4)}`;
  return neg && q !== 0n ? `-${body}` : body;
}

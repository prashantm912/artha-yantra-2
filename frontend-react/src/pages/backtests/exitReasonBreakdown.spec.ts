import { describe, expect, it } from 'vitest';
import type { TradeRow } from '../../api/backtests.ts';
import { exitReasonBreakdown } from './exitReasonBreakdown.ts';

/** A minimal trade — only exitReason + pnl feed the breakdown; the rest are inert placeholders. */
function row(exitReason: string, pnl: string): TradeRow {
  return {
    seq: 0,
    side: 'LONG',
    qty: 1,
    entryTs: '2026-01-05T09:15:00+05:30',
    entryPrice: '100',
    exitTs: '2026-01-05T09:45:00+05:30',
    exitPrice: '101',
    pnl,
    pnlPct: '1',
    exitReason,
    barsHeld: 30,
  };
}

describe('exitReasonBreakdown', () => {
  it('groups mixed-casing reasons into ONE bucket (chip task_cf5d58c8)', () => {
    // a lingering lowercase row (pre-backfill / stale cache) must bucket with its UPPERCASE twin
    const stats = exitReasonBreakdown([
      row('stop_loss', '10'),
      row('STOP_LOSS', '-4'),
      row('Stop_Loss', '6'),
    ]);

    expect(stats).toHaveLength(1);
    expect(stats[0].reason).toBe('STOP_LOSS');
    expect(stats[0].count).toBe(3);
    expect(stats[0].wins).toBe(2); // +10 and +6 win; -4 loses
    expect(stats[0].totalPnl).toBe('12'); // addDecimal keeps the inputs' (zero) fraction scale
  });

  it('keeps genuinely distinct reasons separate and sorts by count desc', () => {
    const stats = exitReasonBreakdown([
      row('TAKE_PROFIT', '5'),
      row('take_profit', '5'),
      row('STOP_LOSS', '-3'),
    ]);

    expect(stats.map((s) => s.reason)).toEqual(['TAKE_PROFIT', 'STOP_LOSS']);
    expect(stats[0].count).toBe(2);
    expect(stats[1].count).toBe(1);
  });
});

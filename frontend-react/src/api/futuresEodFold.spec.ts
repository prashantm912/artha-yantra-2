import { describe, expect, it } from 'vitest';
import {
  foldFuturesEod,
  orderContractsFrontFirst,
  stitchContinuousFrontMonth,
} from './futuresEodFold.ts';
import type { FutEodRow } from './types.ts';

function row(p: Partial<FutEodRow>): FutEodRow {
  return {
    tradingsymbol: 'NIFTY26JUNFUT',
    tradeDate: '2026-06-15',
    open: '100', high: '110', low: '95', close: '105',
    oiClose: 1000, oiChange: 0, volume: 500,
    ...p,
  };
}

describe('foldFuturesEod', () => {
  it('computes day-over-day deltas, day range and interpretation; newest first', () => {
    const out = foldFuturesEod([
      row({ tradeDate: '2026-06-15', close: '105', oiClose: 1000 }),
      row({ tradeDate: '2026-06-16', open: '105', high: '115', low: '104', close: '112', oiClose: 1200 }),
    ]);
    expect(out.map((r) => r.tradeDate)).toEqual(['2026-06-16', '2026-06-15']); // newest first
    const d2 = out[0];
    expect(d2.ltpChange).toBe('7'); // 112 − 105
    expect(d2.oiChange).toBe(200); // 1200 − 1000
    expect(d2.dayRange).toBe('11'); // 115 − 104
    expect(d2.dayRangePct).toBe('9.82'); // 11 / 112 * 100
    expect(d2.interpretation).toBe('LONG_BUILDUP'); // price up + OI up
  });

  it('leaves the oldest row deltas null (no prior day)', () => {
    const out = foldFuturesEod([row({ tradeDate: '2026-06-15' })]);
    expect(out[0].ltpChange).toBeNull();
    expect(out[0].oiChange).toBeNull();
    expect(out[0].interpretation).toBeNull();
  });
});

describe('stitchContinuousFrontMonth', () => {
  it('takes each session from its front contract and rolls at expiry (§10.2-4 Wave 5)', () => {
    const { rows, contractByDate } = stitchContinuousFrontMonth([
      // JUN is the front while it still produces rows; JUL exists alongside.
      row({ tradeDate: '2026-06-24', tradingsymbol: 'NIFTY26JUNFUT', close: '100', oiClose: 900 }),
      row({ tradeDate: '2026-06-24', tradingsymbol: 'NIFTY26JULFUT', close: '101', oiClose: 100 }),
      row({ tradeDate: '2026-06-25', tradingsymbol: 'NIFTY26JUNFUT', close: '102', oiClose: 800 }),
      row({ tradeDate: '2026-06-25', tradingsymbol: 'NIFTY26JULFUT', close: '103', oiClose: 200 }),
      // JUN expired — only JUL produces rows → the stitch rolls.
      row({ tradeDate: '2026-06-26', tradingsymbol: 'NIFTY26JULFUT', close: '104', oiClose: 300 }),
    ]);
    expect(rows.map((r) => r.tradeDate)).toEqual(['2026-06-26', '2026-06-25', '2026-06-24']);
    expect(contractByDate.get('2026-06-25')).toBe('NIFTY26JUNFUT');
    expect(contractByDate.get('2026-06-26')).toBe('NIFTY26JULFUT');
    // pre-roll deltas stay within JUN (102 − 100); the roll session's deltas span the two contracts.
    expect(rows[1].ltpChange).toBe('2');
    expect(rows[0].ltpChange).toBe('2'); // 104 (JUL) − 102 (JUN)
    expect(rows[0].oiChange).toBe(-500); // 300 (JUL) − 800 (JUN)
  });
});

describe('orderContractsFrontFirst', () => {
  it('puts the FRONT month first: alive-on-latest-session, then nearest expiry (§10.2-4)', () => {
    // JUN expired (no rows on the latest session) but has the MOST rows — the old
    // most-captured-rows heuristic would have picked it (or a far month on a tie).
    const rows = [
      row({ tradingsymbol: 'NIFTY26JUNFUT', tradeDate: '2026-06-28' }),
      row({ tradingsymbol: 'NIFTY26JUNFUT', tradeDate: '2026-06-29' }),
      row({ tradingsymbol: 'NIFTY26JUNFUT', tradeDate: '2026-06-30' }),
      row({ tradingsymbol: 'NIFTY26AUGFUT', tradeDate: '2026-07-01' }),
      row({ tradingsymbol: 'NIFTY26AUGFUT', tradeDate: '2026-07-02' }),
      row({ tradingsymbol: 'NIFTY26JULFUT', tradeDate: '2026-07-01' }),
      row({ tradingsymbol: 'NIFTY26JULFUT', tradeDate: '2026-07-02' }),
    ];
    expect(orderContractsFrontFirst(rows)).toEqual([
      'NIFTY26JULFUT', // front: alive + nearest expiry
      'NIFTY26AUGFUT',
      'NIFTY26JUNFUT', // expired sorts after the live contracts
    ]);
  });

  it('year rolls over correctly (DEC26 before JAN27) and empty input yields empty', () => {
    const rows = [
      row({ tradingsymbol: 'NIFTY27JANFUT', tradeDate: '2026-12-30' }),
      row({ tradingsymbol: 'NIFTY26DECFUT', tradeDate: '2026-12-30' }),
    ];
    expect(orderContractsFrontFirst(rows)).toEqual(['NIFTY26DECFUT', 'NIFTY27JANFUT']);
    expect(orderContractsFrontFirst([])).toEqual([]);
  });
});

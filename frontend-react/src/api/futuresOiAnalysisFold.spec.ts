import { describe, expect, it } from 'vitest';
import { foldFuturesOiAnalysis } from './futuresOiAnalysisFold.ts';
import type { FutSeriesPoint } from './types.ts';

const B = (m: number) => `2026-06-20T09:${String(15 + m).padStart(2, '0')}:00+05:30`;

function pt(p: Partial<FutSeriesPoint>): FutSeriesPoint {
  return {
    bucket: B(0),
    tradingsymbol: 'NIFTY26JUNFUT',
    ltp: '100',
    oi: 1000,
    oiChange: 0,
    dayOpen: '100',
    dayHigh: '101',
    dayLow: '99',
    prevClose: '99',
    volume: 10,
    expiry: '2026-06-25',
    ...p,
  };
}

describe('foldFuturesOiAnalysis', () => {
  it('derives per-interval deltas, day-open cum OI, interval volume and level breaks; oldest-first', () => {
    const out = foldFuturesOiAnalysis([
      pt({ bucket: B(0), ltp: '100', oi: 1000, volume: 10, dayHigh: '101', dayLow: '99' }),
      pt({ bucket: B(5), ltp: '110', oi: 1200, volume: 25, dayHigh: '111', dayLow: '99' }),
      pt({ bucket: B(10), ltp: '108', oi: 1150, volume: 40, dayHigh: '111', dayLow: '97' }),
    ]);
    expect(out.map((r) => r.bucket)).toEqual([B(0), B(5), B(10)]); // oldest-first

    // Oldest row: no prior bucket → deltas null; cum OI baseline = itself (0).
    expect(out[0].oiChange).toBeNull();
    expect(out[0].ltpChange).toBeNull();
    expect(out[0].volume).toBeNull();
    expect(out[0].totalChngInOi).toBe(0);
    expect(out[0].interpretation).toBeNull();
    expect(out[0].dhBreak).toBe(false);

    // Bucket 1: price up + OI up, a new session high.
    expect(out[1].ltpChange).toBe('10'); // 110 − 100
    expect(out[1].oiChange).toBe(200); // 1200 − 1000
    expect(out[1].totalChngInOi).toBe(200); // 1200 − first-of-day 1000
    expect(out[1].volume).toBe(15); // 25 − 10 (interval delta)
    expect(out[1].interpretation).toBe('LONG_BUILDUP');
    expect(out[1].dhBreak).toBe(true);
    expect(out[1].breakLevel).toBe('111'); // the new session high

    // Bucket 2: price down + OI down, a new session low (day_high flat → no high break).
    expect(out[2].ltpChange).toBe('-2'); // 108 − 110
    expect(out[2].oiChange).toBe(-50);
    expect(out[2].totalChngInOi).toBe(150); // 1150 − 1000
    expect(out[2].volume).toBe(15); // 40 − 25
    expect(out[2].interpretation).toBe('LONG_UNWINDING');
    expect(out[2].dhBreak).toBe(false);
    expect(out[2].dlBreak).toBe(true);
    expect(out[2].breakLevel).toBe('97'); // the new session low
  });

  it('classifies all four OI-interpretation states from sign(ΔLTP) × sign(ΔOI)', () => {
    const last = (a: Partial<FutSeriesPoint>, b: Partial<FutSeriesPoint>) =>
      foldFuturesOiAnalysis([pt({ bucket: B(0), ...a }), pt({ bucket: B(5), ...b })])[1].interpretation;
    expect(last({ ltp: '100', oi: 1000 }, { ltp: '110', oi: 1100 })).toBe('LONG_BUILDUP'); // ↑ ↑
    expect(last({ ltp: '100', oi: 1000 }, { ltp: '110', oi: 900 })).toBe('SHORT_COVERING'); // ↑ ↓
    expect(last({ ltp: '110', oi: 1000 }, { ltp: '100', oi: 1100 })).toBe('SHORT_BUILDUP'); // ↓ ↑
    expect(last({ ltp: '110', oi: 1000 }, { ltp: '100', oi: 900 })).toBe('LONG_UNWINDING'); // ↓ ↓
  });

  it('on a wide bucket that breaks BOTH extremes, the high-break wins the badge level', () => {
    const out = foldFuturesOiAnalysis([
      pt({ bucket: B(0), ltp: '100', oi: 1000, dayHigh: '101', dayLow: '99' }),
      pt({ bucket: B(5), ltp: '105', oi: 1100, dayHigh: '111', dayLow: '95' }), // new high AND new low
    ]);
    expect(out[1].dhBreak).toBe(true);
    expect(out[1].dlBreak).toBe(true);
    expect(out[1].breakLevel).toBe('111'); // matches the rendered D.H.B badge, not the low
  });

  it('guards the cumulative-volume reset: a day/roll drop yields null interval volume, never a negative', () => {
    const out = foldFuturesOiAnalysis([
      pt({ bucket: B(0), volume: 1000 }),
      pt({ bucket: B(5), volume: 1200 }), // normal increase → 200
      pt({ bucket: B(10), volume: 50 }), // cumulative DROP (reset) → null, not −1150
    ]);
    expect(out[1].volume).toBe(200);
    expect(out[2].volume).toBeNull();
  });

  it('returns empty for no points', () => {
    expect(foldFuturesOiAnalysis([])).toEqual([]);
  });
});

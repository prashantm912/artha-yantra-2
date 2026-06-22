import { describe, expect, it } from 'vitest';
import { foldTrending } from './trendingOiFold.ts';
import type { TrendPoint } from './types.ts';

function pt(p: Partial<TrendPoint>): TrendPoint {
  return {
    bucket: '2026-06-16T09:18:00+05:30',
    totalOi: 0,
    ceOi: 0,
    peOi: 0,
    spot: '100',
    trend: 'FLAT',
    ceLtp: null,
    peLtp: null,
    ...p,
  };
}

describe('foldTrending', () => {
  it('returns empty for no buckets', () => {
    expect(foldTrending([])).toEqual([]);
  });

  it('computes cumulative Δ vs the baseline bucket and Diff = ΔPut − ΔCall (positive = Bullish)', () => {
    const rows = foldTrending([
      pt({ bucket: 'b0', ceOi: 1000, peOi: 1000 }), // baseline
      pt({ bucket: 'b1', ceOi: 1100, peOi: 1300, spot: '57200' }),
    ]);
    expect(rows).toHaveLength(2);
    const last = rows[1];
    expect(last.chngCallOi).toBe(100); // 1100 − 1000
    expect(last.chngPutOi).toBe(300); // 1300 − 1000
    expect(last.diffInOi).toBe(200); // 300 − 100
    expect(last.direction).toBe(1);
    expect(last.netPcr).toBe('1.18'); // 1300 / 1100
    expect(last.sentiment.label).toBe('Bullish');
    expect(last.sentiment.tone).toBe('bull');
  });

  it('flags a Day High Break when Diff prints a new positive extreme above the prior bucket', () => {
    const rows = foldTrending([
      pt({ bucket: 'b0', ceOi: 1000, peOi: 1000 }),
      pt({ bucket: 'b1', ceOi: 1000, peOi: 1200, spot: '57250' }), // diff +200, new max, >0
    ]);
    expect(rows[1].dhBreak).toBe(true);
    expect(rows[1].breakLevel).toBe('57250');
  });

  it('computes the PA premium columns: Total + Δ CE/PE Ltp + the CE+PE straddle change', () => {
    const rows = foldTrending([
      pt({ bucket: 'b0', ceOi: 1000, peOi: 1000, ceLtp: '9500', peLtp: '11000' }), // baseline
      pt({ bucket: 'b1', ceOi: 1100, peOi: 1300, ceLtp: '9511.2', peLtp: '11944.6' }),
    ]);
    const last = rows[1];
    expect(last.totalCallLtp).toBe('9511.2');
    expect(last.totalPutLtp).toBe('11944.6');
    expect(last.callLtpChng).toBe('11.2'); // 9511.2 − 9500
    expect(last.putLtpChng).toBe('944.6'); // 11944.6 − 11000
    expect(last.straddleChng).toBe('955.8'); // 11.2 + 944.6
    // the baseline row has no prior → its Δ premiums are 0 (subtract from itself).
    expect(rows[0].callLtpChng).toBe('0');
  });

  it('reads bearish when calls dominate (negative Diff)', () => {
    const rows = foldTrending([
      pt({ bucket: 'b0', ceOi: 1000, peOi: 1000 }),
      pt({ bucket: 'b1', ceOi: 1400, peOi: 1100 }), // ΔCall +400 > ΔPut +100 → diff −300
    ]);
    expect(rows[1].diffInOi).toBe(-300);
    expect(rows[1].sentiment.label).toBe('Bearish');
  });
});

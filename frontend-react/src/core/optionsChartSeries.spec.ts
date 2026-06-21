import { describe, expect, it } from 'vitest';
import { toOptChartSeries } from './optionsChartSeries.ts';
import type { OptCandle } from '../api/types.ts';

function candle(p: Partial<OptCandle>): OptCandle {
  return {
    time: '2026-06-15T09:15:00+05:30',
    open: '100',
    high: '110',
    low: '95',
    close: '105',
    volume: 500,
    oi: 1000,
    iv: '13.5',
    ...p,
  };
}

describe('toOptChartSeries', () => {
  it('folds premium candles to [open,close,low,high], passes OI, computes VWAP, finds day extrema', () => {
    const s = toOptChartSeries([
      candle({ time: '2026-06-15T09:15:00+05:30', open: '100', high: '110', low: '95', close: '105', volume: 100, oi: 1000 }),
      candle({ time: '2026-06-15T09:20:00+05:30', open: '105', high: '120', low: '104', close: '115', volume: 300, oi: 1200 }),
      candle({ time: '2026-06-15T09:25:00+05:30', open: '115', high: '116', low: '90', close: '92', volume: 100, oi: null }),
    ]);
    expect(s.times).toEqual(['09:15', '09:20', '09:25']);
    expect(s.candles[0]).toEqual([100, 105, 95, 110]); // [open, close, low, high]
    expect(s.oi).toEqual([1000, 1200, null]); // null gaps (bridged in the chart)
    expect(s.dayHigh).toEqual({ index: 1, value: 120 });
    expect(s.dayLow).toEqual({ index: 2, value: 90 });
    // VWAP cumulative Σ close·vol / Σ vol: 105 → (105·100+115·300)/400 = 112.5 → (…+92·100)/500 = 108.4
    expect(s.vwap[0]).toBe(105);
    expect(s.vwap[1]).toBeCloseTo(112.5, 6);
    expect(s.vwap[2]).toBeCloseTo(108.4, 6);
  });

  it('returns empty arrays and null extrema for no candles', () => {
    const s = toOptChartSeries([]);
    expect(s.times).toEqual([]);
    expect(s.candles).toEqual([]);
    expect(s.oi).toEqual([]);
    expect(s.vwap).toEqual([]);
    expect(s.dayHigh).toBeNull();
    expect(s.dayLow).toBeNull();
  });
});

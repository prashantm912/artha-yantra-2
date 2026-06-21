import { describe, expect, it } from 'vitest';
import { toFutOiChartSeries } from './futuresOiChartSeries.ts';
import type { FutOiCandle } from '../api/types.ts';

function candle(p: Partial<FutOiCandle>): FutOiCandle {
  return {
    time: '2026-06-15T09:15:00+05:30',
    open: '100',
    high: '110',
    low: '95',
    close: '105',
    volume: 500,
    oi: 1000,
    ...p,
  };
}

describe('toFutOiChartSeries', () => {
  it('folds candles to [open,close,low,high], passes OI through, and finds the day extrema', () => {
    const s = toFutOiChartSeries([
      candle({ time: '2026-06-15T09:15:00+05:30', open: '100', high: '110', low: '95', close: '105', oi: 1000 }),
      candle({ time: '2026-06-15T09:20:00+05:30', open: '105', high: '120', low: '104', close: '118', oi: 1200 }),
      candle({ time: '2026-06-15T09:25:00+05:30', open: '118', high: '119', low: '90', close: '92', oi: null }),
    ]);
    expect(s.times).toEqual(['09:15', '09:20', '09:25']);
    expect(s.candles[0]).toEqual([100, 105, 95, 110]); // [open, close, low, high]
    expect(s.oi).toEqual([1000, 1200, null]); // null gaps the OI line
    expect(s.dayHigh).toEqual({ index: 1, value: 120 }); // highest high
    expect(s.dayLow).toEqual({ index: 2, value: 90 }); // lowest low
  });

  it('returns empty arrays and null extrema for no candles', () => {
    const s = toFutOiChartSeries([]);
    expect(s.times).toEqual([]);
    expect(s.candles).toEqual([]);
    expect(s.oi).toEqual([]);
    expect(s.dayHigh).toBeNull();
    expect(s.dayLow).toBeNull();
  });
});

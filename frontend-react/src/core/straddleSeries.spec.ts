import { describe, expect, it } from 'vitest';
import { toStraddleSeries } from './straddleSeries.ts';
import type { StraddleCandle } from '../api/types.ts';

function candle(p: Partial<StraddleCandle>): StraddleCandle {
  return {
    time: '2026-06-15T09:15:00+05:30',
    open: '100',
    high: '110',
    low: '95',
    close: '105',
    ceClose: '60',
    peClose: '45',
    volume: 100,
    ...p,
  };
}

describe('toStraddleSeries', () => {
  it('maps candles in ECharts [open, close, low, high] order and HH:MM labels', () => {
    const s = toStraddleSeries([
      candle({ time: '2026-06-15T09:18:00+05:30', open: '100', close: '105', low: '95', high: '110' }),
    ]);
    expect(s.times).toEqual(['09:18']);
    expect(s.candles).toEqual([[100, 105, 95, 110]]);
    expect(s.call).toEqual([60]);
    expect(s.put).toEqual([45]);
  });

  it('computes cumulative volume-weighted VWAP', () => {
    const s = toStraddleSeries([
      candle({ close: '100', volume: 100 }),
      candle({ close: '200', volume: 300 }),
    ]);
    // bucket 0: 100; bucket 1: (100·100 + 200·300)/400 = 70000/400 = 175
    expect(s.vwap[0]).toBeCloseTo(100);
    expect(s.vwap[1]).toBeCloseTo(175);
  });

  it('seeds the 20-EMA on the first close', () => {
    const s = toStraddleSeries([candle({ close: '100' }), candle({ close: '130' })]);
    const k = 2 / 21;
    expect(s.ema20[0]).toBeCloseTo(100);
    expect(s.ema20[1]).toBeCloseTo(130 * k + 100 * (1 - k));
  });

  it('flat-carries VWAP across a zero-volume bucket', () => {
    const s = toStraddleSeries([candle({ close: '50', volume: 0 })]);
    expect(s.vwap[0]).toBeCloseTo(50);
  });

  it('finds the day high and day low extrema with their indices', () => {
    const s = toStraddleSeries([
      candle({ high: '120', low: '100' }),
      candle({ high: '150', low: '90' }),
      candle({ high: '140', low: '110' }),
    ]);
    expect(s.dayHigh).toEqual({ index: 1, value: 150 });
    expect(s.dayLow).toEqual({ index: 1, value: 90 });
  });

  it('returns null extrema for an empty series', () => {
    const s = toStraddleSeries([]);
    expect(s.dayHigh).toBeNull();
    expect(s.dayLow).toBeNull();
    expect(s.times).toEqual([]);
  });
});

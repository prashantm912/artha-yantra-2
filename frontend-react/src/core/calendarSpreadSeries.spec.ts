import { describe, expect, it } from 'vitest';
import { toSpreadSeries } from './calendarSpreadSeries.ts';
import type { SpreadCandle } from '../api/types.ts';

function bar(
  time: string,
  open: string,
  high: string,
  low: string,
  close: string,
  nearClose: string,
  farClose: string,
  volume: number,
): SpreadCandle {
  return { time, open, high, low, close, nearClose, farClose, volume };
}

describe('toSpreadSeries', () => {
  it('folds spread candles into ECharts [open, close, low, high] order with near/far lines', () => {
    const s = toSpreadSeries([
      bar('2026-06-26T09:15:00+05:30', '-20', '0', '-40', '-20', '108', '128', 50),
      bar('2026-06-26T09:18:00+05:30', '-18', '-5', '-30', '-12', '120', '132', 40),
    ]);

    expect(s.times).toEqual(['09:15', '09:18']);
    expect(s.candles[0]).toEqual([-20, -20, -40, 0]); // [open, close, low, high]
    expect(s.near).toEqual([108, 120]);
    expect(s.far).toEqual([128, 132]);
  });

  it('tracks the day high/low of the spread', () => {
    const s = toSpreadSeries([
      bar('2026-06-26T09:15:00+05:30', '-20', '0', '-40', '-20', '108', '128', 50),
      bar('2026-06-26T09:18:00+05:30', '-18', '5', '-30', '-12', '120', '132', 40),
    ]);

    expect(s.dayHigh).toEqual({ index: 1, value: 5 });
    expect(s.dayLow).toEqual({ index: 0, value: -40 });
  });

  it('volume-weights the spread close into the VWAP', () => {
    // closes -20 @vol10, -40 @vol30 → (-20·10 + -40·30)/40 = -1400/40 = -35.
    const s = toSpreadSeries([
      bar('2026-06-26T09:15:00+05:30', '-20', '0', '-40', '-20', '1', '1', 10),
      bar('2026-06-26T09:18:00+05:30', '-40', '-30', '-50', '-40', '1', '1', 30),
    ]);
    expect(s.vwap[1]).toBeCloseTo(-35, 6);
  });

  it('returns empty arrays + null extrema for an empty series', () => {
    const s = toSpreadSeries([]);
    expect(s.candles).toEqual([]);
    expect(s.dayHigh).toBeNull();
    expect(s.dayLow).toBeNull();
  });
});

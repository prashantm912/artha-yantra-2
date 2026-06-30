import { describe, expect, it } from 'vitest';
import {
  rsi,
  smaOfLine,
  superTrend,
  volumeMa,
  vwap,
  vwma,
  type IndicatorBar,
  type LinePoint,
} from './indicators.ts';

/** Build flat OHLC bars (h=l=c) at 1-minute spacing from a base time, with constant volume. */
function flat(closes: number[], vol = 100, base = 0): IndicatorBar[] {
  return closes.map((c, i) => ({ time: base + i * 60, open: c, high: c, low: c, close: c, volume: vol }));
}

/** Build trending bars with a ±1 wick around each close. */
function wicked(closes: number[], vol = 100): IndicatorBar[] {
  return closes.map((c, i) => ({ time: i * 60, open: c, high: c + 1, low: c - 1, close: c, volume: vol }));
}

const val = (p: LinePoint): number | undefined => ('value' in p ? p.value : undefined);

describe('vwap', () => {
  it('accumulates typical-price × volume within a session', () => {
    const out = vwap(flat([10, 20], 100));
    expect(val(out[0])).toBe(10);
    expect(val(out[1])).toBe(15); // (10*100 + 20*100) / 200
  });

  it('resets at the IST day boundary', () => {
    // Two bars on one IST day, then one bar a full day later → the third restarts the accumulator.
    const day1 = flat([10, 20], 100, 0);
    const day2 = flat([30], 100, 86_400);
    const out = vwap([...day1, ...day2]);
    expect(val(out[2])).toBe(30); // fresh session: just the new bar's typical price
  });
});

describe('vwma / volumeMa', () => {
  it('vwma is whitespace until warmed up, then volume-weighted', () => {
    const bars: IndicatorBar[] = [
      { time: 0, open: 0, high: 0, low: 0, close: 10, volume: 1 },
      { time: 60, open: 0, high: 0, low: 0, close: 20, volume: 3 },
      { time: 120, open: 0, high: 0, low: 0, close: 30, volume: 1 },
    ];
    const out = vwma(bars, 2);
    expect(val(out[0])).toBeUndefined();
    expect(val(out[1])).toBe((10 * 1 + 20 * 3) / 4); // 17.5
    expect(val(out[2])).toBe((20 * 3 + 30 * 1) / 4); // 22.5
  });

  it('volumeMa is the simple mean of volume over the window', () => {
    const bars = flat([1, 1, 1], 0).map((b, i) => ({ ...b, volume: [10, 20, 30][i] }));
    const out = volumeMa(bars, 2);
    expect(val(out[0])).toBeUndefined();
    expect(val(out[1])).toBe(15);
    expect(val(out[2])).toBe(25);
  });
});

describe('rsi', () => {
  it('is whitespace during warm-up', () => {
    const out = rsi(flat([1, 2, 3, 4]), 2);
    expect(val(out[0])).toBeUndefined();
    expect(val(out[1])).toBeUndefined();
    expect(val(out[2])).toBe(100);
  });

  it('is 100 on a pure uptrend and 0 on a pure downtrend', () => {
    expect(val(rsi(flat([1, 2, 3, 4, 5]), 2).at(-1)!)).toBe(100);
    expect(val(rsi(flat([5, 4, 3, 2, 1]), 2).at(-1)!)).toBe(0);
  });

  it('lands between the extremes on a mixed series', () => {
    const v = val(rsi(flat([10, 11, 10, 12, 11, 13, 12, 14, 13, 15, 14, 16, 15, 17, 16]), 14).at(-1)!);
    expect(v).toBeGreaterThan(0);
    expect(v).toBeLessThan(100);
  });
});

describe('smaOfLine', () => {
  it('averages only the defined values and warms up over length', () => {
    const points: LinePoint[] = [{ time: 0 }, { time: 1, value: 10 }, { time: 2, value: 20 }, { time: 3, value: 30 }];
    const out = smaOfLine(points, 2);
    expect(val(out[0])).toBeUndefined();
    expect(val(out[1])).toBeUndefined(); // only one defined value so far
    expect(val(out[2])).toBe(15);
    expect(val(out[3])).toBe(25);
  });
});

describe('superTrend', () => {
  it('tracks below price in an uptrend (dir up)', () => {
    const bars = wicked([10, 12, 14, 16, 18, 20]);
    const st = superTrend(bars, 2, 1);
    expect(st.length).toBe(bars.length - 2);
    for (const p of st) {
      expect(p.dir).toBe('up');
      const bar = bars.find((b) => b.time === p.time)!;
      expect(p.value).toBeLessThan(bar.close);
    }
  });

  it('establishes a downtrend (the trailing stop lags, then sits above price)', () => {
    // SuperTrend's stop is sticky — a fresh downtrend stays "up" until the close breaks the lower band,
    // so only once the trend is established does the line flip above price.
    const bars = wicked([20, 18, 16, 14, 12, 10, 8, 6]);
    const st = superTrend(bars, 2, 1);
    const last = st.at(-1)!;
    expect(last.dir).toBe('down');
    const bar = bars.find((b) => b.time === last.time)!;
    expect(last.value).toBeGreaterThan(bar.close);
    expect(st.some((p) => p.dir === 'down')).toBe(true);
  });

  it('flips direction when the close pierces the opposite band', () => {
    const st = superTrend(wicked([10, 11, 12, 13, 14, 4, 3, 2]), 2, 1);
    expect(st.at(-1)!.dir).toBe('down');
  });
});

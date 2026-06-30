// Pure technical-indicator math for the Advance Chart (oipulse §advance-chart default studies): session
// VWAP, VWMA(20), SuperTrend(10,2), RSI(14)+SMA(14), and the OSPL-Volume MA(20). All functions are pure
// over an ascending bar array and emit lightweight-charts line points — a defined {time,value} where the
// indicator has warmed up, else a whitespace {time} so the chart breaks the line instead of drawing a
// connector across the warm-up gap. The chart binds these to LineSeries overlays; this module is what the
// spec pins (the rendering is declarative, the math is where bugs hide).

/** Minimal numeric OHLCV bar (epoch-seconds time) the indicators consume. */
export interface IndicatorBar {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

/** A lightweight-charts line point — a value, or whitespace (time only) to break the line. */
export type LinePoint = { time: number; value: number } | { time: number };

/** One SuperTrend bar: the trailing-stop level + the current trend direction. */
export interface SuperTrendPoint {
  time: number;
  value: number;
  dir: 'up' | 'down';
}

const IST_OFFSET_SEC = 19_800; // +05:30

/** The IST calendar day a bar belongs to (for the session VWAP reset). */
function istDay(timeSec: number): number {
  return Math.floor((timeSec + IST_OFFSET_SEC) / 86_400);
}

/**
 * Session-anchored VWAP — cumulative Σ(typicalPrice × volume) / Σ(volume), reset at each IST day
 * boundary (the intraday anchor). Typical price = (high + low + close) / 3. A bar with no accumulated
 * volume yet emits whitespace.
 */
export function vwap(bars: IndicatorBar[]): LinePoint[] {
  let day = NaN;
  let cumPv = 0;
  let cumVol = 0;
  return bars.map((b) => {
    const d = istDay(b.time);
    if (d !== day) {
      day = d;
      cumPv = 0;
      cumVol = 0;
    }
    const tp = (b.high + b.low + b.close) / 3;
    cumPv += tp * b.volume;
    cumVol += b.volume;
    return cumVol > 0 ? { time: b.time, value: cumPv / cumVol } : { time: b.time };
  });
}

/**
 * Volume-weighted moving average over the last {@code length} bars: Σ(close × volume) / Σ(volume).
 * Warms up at index {@code length − 1}; a zero-volume window emits whitespace.
 */
export function vwma(bars: IndicatorBar[], length = 20): LinePoint[] {
  return bars.map((b, i) => {
    if (i < length - 1) {
      return { time: b.time };
    }
    let pv = 0;
    let v = 0;
    for (let k = i - length + 1; k <= i; k++) {
      pv += bars[k].close * bars[k].volume;
      v += bars[k].volume;
    }
    return v > 0 ? { time: b.time, value: pv / v } : { time: b.time };
  });
}

/** Simple moving average of the volume over {@code length} bars (the OSPL-Volume MA overlay). */
export function volumeMa(bars: IndicatorBar[], length = 20): LinePoint[] {
  return bars.map((b, i) => {
    if (i < length - 1) {
      return { time: b.time };
    }
    let s = 0;
    for (let k = i - length + 1; k <= i; k++) {
      s += bars[k].volume;
    }
    return { time: b.time, value: s / length };
  });
}

/**
 * Wilder RSI over {@code period} bars (default 14). Defined from index {@code period}; earlier bars emit
 * whitespace. A zero average-loss window is RSI 100 (no down moves).
 */
export function rsi(bars: IndicatorBar[], period = 14): LinePoint[] {
  const out: LinePoint[] = bars.map((b) => ({ time: b.time }));
  if (bars.length <= period) {
    return out;
  }
  let gain = 0;
  let loss = 0;
  for (let i = 1; i <= period; i++) {
    const ch = bars[i].close - bars[i - 1].close;
    if (ch >= 0) gain += ch;
    else loss -= ch;
  }
  let avgGain = gain / period;
  let avgLoss = loss / period;
  out[period] = { time: bars[period].time, value: rsiValue(avgGain, avgLoss) };
  for (let i = period + 1; i < bars.length; i++) {
    const ch = bars[i].close - bars[i - 1].close;
    const up = ch > 0 ? ch : 0;
    const dn = ch < 0 ? -ch : 0;
    avgGain = (avgGain * (period - 1) + up) / period;
    avgLoss = (avgLoss * (period - 1) + dn) / period;
    out[i] = { time: bars[i].time, value: rsiValue(avgGain, avgLoss) };
  }
  return out;
}

function rsiValue(avgGain: number, avgLoss: number): number {
  if (avgLoss === 0) {
    return 100;
  }
  const rs = avgGain / avgLoss;
  return 100 - 100 / (1 + rs);
}

/**
 * Simple moving average of an indicator's defined values (e.g. RSI → its SMA(14) signal line), preserving
 * the input times. Whitespace inputs (warm-up gaps) don't count toward the window and the SMA stays
 * whitespace until {@code length} defined values have accrued.
 */
export function smaOfLine(points: LinePoint[], length: number): LinePoint[] {
  const defined: number[] = [];
  return points.map((p) => {
    if (!('value' in p)) {
      return { time: p.time };
    }
    defined.push(p.value);
    if (defined.length < length) {
      return { time: p.time };
    }
    let s = 0;
    for (let k = defined.length - length; k < defined.length; k++) {
      s += defined[k];
    }
    return { time: p.time, value: s / length };
  });
}

/**
 * SuperTrend(period, multiplier) — the ATR-banded trend-following stop (oipulse default 10, 2). Returns a
 * per-bar trailing level + direction from index {@code period} (earlier bars are omitted). The line sits
 * BELOW price in an uptrend (the final lower band) and ABOVE it in a downtrend (the final upper band);
 * the chart splits it into a green up-segment series and a red down-segment series.
 */
export function superTrend(bars: IndicatorBar[], period = 10, multiplier = 2): SuperTrendPoint[] {
  const n = bars.length;
  if (n <= period) {
    return [];
  }
  // Wilder ATR.
  const tr = new Array<number>(n);
  tr[0] = bars[0].high - bars[0].low;
  for (let i = 1; i < n; i++) {
    const h = bars[i].high;
    const l = bars[i].low;
    const pc = bars[i - 1].close;
    tr[i] = Math.max(h - l, Math.abs(h - pc), Math.abs(l - pc));
  }
  const atr = new Array<number>(n).fill(NaN);
  let seed = 0;
  for (let i = 1; i <= period; i++) seed += tr[i];
  atr[period] = seed / period;
  for (let i = period + 1; i < n; i++) {
    atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period;
  }

  const out: SuperTrendPoint[] = [];
  let finalUpper = 0;
  let finalLower = 0;
  let dir: 'up' | 'down' = 'up';
  for (let i = period; i < n; i++) {
    const mid = (bars[i].high + bars[i].low) / 2;
    const basicUpper = mid + multiplier * atr[i];
    const basicLower = mid - multiplier * atr[i];
    if (i === period) {
      finalUpper = basicUpper;
      finalLower = basicLower;
      dir = bars[i].close >= mid ? 'up' : 'down';
    } else {
      const prevClose = bars[i - 1].close;
      finalUpper =
        basicUpper < finalUpper || prevClose > finalUpper ? basicUpper : finalUpper;
      finalLower =
        basicLower > finalLower || prevClose < finalLower ? basicLower : finalLower;
      // Flip the trend when the close pierces the opposite final band.
      if (dir === 'up' && bars[i].close < finalLower) {
        dir = 'down';
      } else if (dir === 'down' && bars[i].close > finalUpper) {
        dir = 'up';
      }
    }
    out.push({ time: bars[i].time, value: dir === 'up' ? finalLower : finalUpper, dir });
  }
  return out;
}

import type { StraddleCandle } from '../api/types.ts';

// Straddle-chart series maths (§20.7.6 — oipulse "Straddle Chart"). Folds the combined CE+PE premium
// candles into the ECharts plotting arrays plus the two overlays oipulse draws server-side: a
// volume-weighted VWAP and a 20-period EMA on the straddle close, with the Call/Put close lines and
// the day high/low extrema.
//
// NOTE on Number(): money is a decimal STRING everywhere it is displayed or compared (lib/decimal),
// but a chart needs numeric COORDINATES — this module is the single sanctioned string→number boundary,
// used only to feed pixel positions (never for a displayed value, which stays string-formatted).

const EMA_PERIOD = 20;

/** The plotting arrays for one straddle/strangle series. */
export interface StraddleSeries {
  /** HH:MM category labels (IST), one per interval. */
  times: string[];
  /** ECharts candlestick order: [open, close, low, high]. */
  candles: number[][];
  /** Volume-weighted average of the straddle close (cumulative Σ close·vol / Σ vol). */
  vwap: number[];
  /** 20-period EMA of the straddle close. */
  ema20: number[];
  /** Per-interval CE close (the Call Price line). */
  call: number[];
  /** Per-interval PE close (the Put Price line). */
  put: number[];
  /** Day high/low of the straddle (markPoint anchors); null on an empty series. */
  dayHigh: { index: number; value: number } | null;
  dayLow: { index: number; value: number } | null;
}

/** "2026-06-15T09:18:00+05:30" → "09:18" (the HH:MM in the carried offset, no Date parse). */
function hhmm(iso: string): string {
  return iso.length >= 16 ? iso.slice(11, 16) : iso;
}

/** Cumulative volume-weighted average of `closes` (oipulse VWAP); flat-carries across zero-volume. */
function vwapOf(closes: number[], volumes: number[]): number[] {
  const out: number[] = [];
  let pv = 0;
  let vol = 0;
  for (let i = 0; i < closes.length; i++) {
    pv += closes[i] * volumes[i];
    vol += volumes[i];
    out.push(vol > 0 ? pv / vol : closes[i]);
  }
  return out;
}

/** Standard EMA(period) seeded on the first close (k = 2/(period+1)). */
function emaOf(closes: number[], period: number): number[] {
  const k = 2 / (period + 1);
  const out: number[] = [];
  let prev = 0;
  for (let i = 0; i < closes.length; i++) {
    prev = i === 0 ? closes[i] : closes[i] * k + prev * (1 - k);
    out.push(prev);
  }
  return out;
}

/** Folds the combined-premium candles into the ECharts plotting arrays + overlays. */
export function toStraddleSeries(items: StraddleCandle[]): StraddleSeries {
  const times: string[] = [];
  const candles: number[][] = [];
  const closes: number[] = [];
  const volumes: number[] = [];
  const call: number[] = [];
  const put: number[] = [];
  let dayHigh: { index: number; value: number } | null = null;
  let dayLow: { index: number; value: number } | null = null;

  items.forEach((c, i) => {
    const open = Number(c.open);
    const high = Number(c.high);
    const low = Number(c.low);
    const close = Number(c.close);
    times.push(hhmm(c.time));
    candles.push([open, close, low, high]);
    closes.push(close);
    volumes.push(c.volume);
    call.push(Number(c.ceClose));
    put.push(Number(c.peClose));
    if (dayHigh === null || high > dayHigh.value) dayHigh = { index: i, value: high };
    if (dayLow === null || low < dayLow.value) dayLow = { index: i, value: low };
  });

  return {
    times,
    candles,
    vwap: vwapOf(closes, volumes),
    ema20: emaOf(closes, EMA_PERIOD),
    call,
    put,
    dayHigh,
    dayLow,
  };
}

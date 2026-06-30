import type { SpreadCandle } from '../api/types.ts';
import { vwapOf } from './straddleSeries.ts';

// Calendar-spread series maths (oipulse "Calendar Spread Chart"). Folds the (near − far) premium
// differential candles into the ECharts plotting arrays plus the overlays the FE draws: a
// volume-weighted VWAP and a 20-period EMA of the spread close, with the near/far close lines and the
// day high/low extrema. The spread can be negative (far premium usually exceeds near at the same
// strike) — the chart plots it as-is.
//
// NOTE on Number(): money is a decimal STRING everywhere it is displayed/compared; this module is the
// single sanctioned string→number boundary, used only to feed chart pixel positions.

const EMA_PERIOD = 20;

/** The plotting arrays for one calendar-spread series. */
export interface SpreadSeries {
  /** HH:MM category labels (IST), one per interval. */
  times: string[];
  /** ECharts candlestick order: [open, close, low, high]. */
  candles: number[][];
  /** Volume-weighted average of the spread close. */
  vwap: number[];
  /** 20-period EMA of the spread close. */
  ema20: number[];
  /** Per-interval near-leg close (the Near line). */
  near: number[];
  /** Per-interval far-leg close (the Far line). */
  far: number[];
  /** Day high/low of the spread (markPoint anchors); null on an empty series. */
  dayHigh: { index: number; value: number } | null;
  dayLow: { index: number; value: number } | null;
}

/** "2026-06-15T09:18:00+05:30" → "09:18" (the HH:MM in the carried offset, no Date parse). */
function hhmm(iso: string): string {
  return iso.length >= 16 ? iso.slice(11, 16) : iso;
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

/** Folds the (near − far) spread candles into the ECharts plotting arrays + overlays. */
export function toSpreadSeries(items: SpreadCandle[]): SpreadSeries {
  const times: string[] = [];
  const candles: number[][] = [];
  const closes: number[] = [];
  const volumes: number[] = [];
  const near: number[] = [];
  const far: number[] = [];
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
    near.push(Number(c.nearClose));
    far.push(Number(c.farClose));
    if (dayHigh === null || high > dayHigh.value) dayHigh = { index: i, value: high };
    if (dayLow === null || low < dayLow.value) dayLow = { index: i, value: low };
  });

  return {
    times,
    candles,
    vwap: vwapOf(closes, volumes),
    ema20: emaOf(closes, EMA_PERIOD),
    near,
    far,
    dayHigh,
    dayLow,
  };
}

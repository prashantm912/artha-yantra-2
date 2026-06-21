import type { FutOiCandle } from '../api/types.ts';

// Futures OI-Chart series maths (§futures/oi-chart — oipulse "Futures Oi Vs. Price Analysis"). Folds the
// per-interval candle+OI rows into the ECharts plotting arrays: the price candlesticks (right axis), the
// OI line (left axis), and the day high/low extrema for the markPoint.
//
// NOTE on Number(): money is a decimal STRING everywhere it is displayed or compared (lib/decimal); a
// chart needs numeric COORDINATES — this module is the single sanctioned string→number boundary, used
// only to feed pixel positions (never a displayed value, which stays string-formatted).

/** The plotting arrays for the OI-chart combo. */
export interface FutOiChartSeries {
  /** HH:MM category labels (IST), one per interval. */
  times: string[];
  /** ECharts candlestick order: [open, close, low, high]. */
  candles: number[][];
  /** The contract OI per interval (null where no OI sample landed on the candle bucket; the OI line
   * bridges those points via connectNulls — a continuous line, faithful to oipulse). */
  oi: (number | null)[];
  /** Day high/low of the price (markPoint anchors); null on an empty series. */
  dayHigh: { index: number; value: number } | null;
  dayLow: { index: number; value: number } | null;
}

/** "2026-06-15T09:18:00+05:30" → "09:18" (the HH:MM in the carried offset, no Date parse). */
function hhmm(iso: string): string {
  return iso.length >= 16 ? iso.slice(11, 16) : iso;
}

/** Folds the candle+OI rows into the ECharts plotting arrays + the day extrema. */
export function toFutOiChartSeries(items: FutOiCandle[]): FutOiChartSeries {
  const times: string[] = [];
  const candles: number[][] = [];
  const oi: (number | null)[] = [];
  let dayHigh: { index: number; value: number } | null = null;
  let dayLow: { index: number; value: number } | null = null;

  items.forEach((c, i) => {
    const open = Number(c.open);
    const high = Number(c.high);
    const low = Number(c.low);
    const close = Number(c.close);
    times.push(hhmm(c.time));
    candles.push([open, close, low, high]);
    oi.push(c.oi);
    if (dayHigh === null || high > dayHigh.value) dayHigh = { index: i, value: high };
    if (dayLow === null || low < dayLow.value) dayLow = { index: i, value: low };
  });

  return { times, candles, oi, dayHigh, dayLow };
}

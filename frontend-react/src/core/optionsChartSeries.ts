import { vwapOf } from './straddleSeries.ts';
import type { OptCandle } from '../api/types.ts';

// Options-Chart per-leg series maths (§options/options-chart). Folds one leg's premium candle+OI rows into
// the ECharts plotting arrays: the premium candlesticks (right axis), the OI line (left axis), the VWAP
// overlay (reused from straddleSeries — pure FE maths, no backend field) and the day high/low extrema.
//
// NOTE on Number(): money is a decimal STRING everywhere it is displayed/compared; a chart needs numeric
// COORDINATES — this is the single sanctioned string→number boundary, used only for pixel positions.
// (IV is intentionally NOT folded here — the IV sub-pane is a deferred follow-up; iv stays on the DTO.)

/** The plotting arrays for one leg's options chart. */
export interface OptChartSeries {
  /** HH:MM category labels (IST), one per interval. */
  times: string[];
  /** ECharts candlestick order: [open, close, low, high]. */
  candles: number[][];
  /** The leg OI per interval (null where no snapshot sample landed on the candle bucket; the line bridges). */
  oi: (number | null)[];
  /** Volume-weighted average of the premium close (Σ close·vol / Σ vol). */
  vwap: number[];
  /** Day high/low of the premium (markPoint anchors); null on an empty series. */
  dayHigh: { index: number; value: number } | null;
  dayLow: { index: number; value: number } | null;
}

/** "2026-06-15T09:18:00+05:30" → "09:18" (the HH:MM in the carried offset, no Date parse). */
function hhmm(iso: string): string {
  return iso.length >= 16 ? iso.slice(11, 16) : iso;
}

/** Folds one leg's candle+OI rows into the ECharts plotting arrays + VWAP + day extrema. */
export function toOptChartSeries(items: OptCandle[]): OptChartSeries {
  const times: string[] = [];
  const candles: number[][] = [];
  const oi: (number | null)[] = [];
  const closes: number[] = [];
  const volumes: number[] = [];
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
    closes.push(close);
    volumes.push(c.volume);
    if (dayHigh === null || high > dayHigh.value) dayHigh = { index: i, value: high };
    if (dayLow === null || low < dayLow.value) dayLow = { index: i, value: low };
  });

  return { times, candles, oi, vwap: vwapOf(closes, volumes), dayHigh, dayLow };
}

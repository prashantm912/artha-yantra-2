import type { MarketCandle } from '../api/types.ts';

// Vix & Index series maths (§features/vix-index — oipulse "Vix & Price Chart"). Folds one minute-series of
// INDIA VIX 1m candles against an index's 1m candles (NIFTY 50 or NIFTY BANK) into the ECharts plotting
// arrays for one dual-axis LINE chart: VIX (left axis) + index price (right axis), aligned on a UNION
// minute axis (left-join — a minute present on only one side bridges via connectNulls, never a 0).
//
// NOTE on Number(): money/price is a decimal STRING everywhere it is displayed or compared (lib/decimal);
// a chart needs numeric COORDINATES — this module is the single sanctioned string→number boundary, used
// only to feed pixel positions (never a displayed value, which stays string-formatted). Mirrors the same
// note on core/futuresOiChartSeries.ts.

/** The plotting arrays for one Vix-vs-Index line chart. */
export interface VixIndexSeries {
  /** HH:MM category labels (IST), the sorted union of both series' minutes. */
  xAxis: string[];
  /** INDIA VIX close per minute (null where no VIX bar landed — connectNulls bridges). */
  vix: (number | null)[];
  /** Index price (close) per minute (null where no index bar landed). */
  price: (number | null)[];
}

/** "2026-06-15T09:18:00+05:30" → "09:18" (the HH:MM in the carried IST offset, no Date parse). */
function hhmm(iso: string): string {
  return iso.length >= 16 ? iso.slice(11, 16) : iso;
}

/** Indexes a candle list by its bucket HH:MM (last write wins on a duplicated minute). */
function byMinute(candles: MarketCandle[] | null | undefined): Map<string, MarketCandle> {
  const m = new Map<string, MarketCandle>();
  for (const c of candles ?? []) m.set(hhmm(c.bucket), c);
  return m;
}

/**
 * Folds the VIX series + one index series into a single dual-axis line chart's arrays. Called twice — once
 * with NIFTY 50 as the price, once with NIFTY BANK. The x-axis is the sorted UNION of both sides' minutes
 * (HH:MM strings sort chronologically within a single IST trading day — always "09:xx".."15:xx", no
 * midnight wrap); each side left-joins onto it, missing minutes → null.
 */
export function foldVixIndex(
  vix: MarketCandle[] | null | undefined,
  price: MarketCandle[] | null | undefined,
): VixIndexSeries {
  const vixBy = byMinute(vix);
  const priceBy = byMinute(price);
  const xAxis = [...new Set([...vixBy.keys(), ...priceBy.keys()])].sort();
  const vixOut = xAxis.map((t) => {
    const c = vixBy.get(t);
    return c ? Number(c.close) : null;
  });
  const priceOut = xAxis.map((t) => {
    const c = priceBy.get(t);
    return c ? Number(c.close) : null;
  });
  return { xAxis, vix: vixOut, price: priceOut };
}

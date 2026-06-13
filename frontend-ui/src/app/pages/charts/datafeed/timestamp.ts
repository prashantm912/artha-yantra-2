/**
 * Datafeed timestamp normalization (E-10.1, A13) — the new countBack-class error magnet, so it
 * lives in its own pure, unit-tested module with ZERO chart-library imports (the E-9 boundary).
 *
 * lightweight-charts renders `UTCTimestamp` in UTC. To make an IST bar show its IST wall-clock time
 * we apply a CONSTANT +05:30 shift to intraday epoch-seconds (so the chart's "UTC" label equals IST
 * wall-clock). Daily/weekly bars use `BusinessDay` and are NOT shifted (LWC time-zone docs). Every
 * conversion is bidirectional — values read back from the chart (visible range, crosshair, markers)
 * must convert the same way.
 */

export const IST_OFFSET_SECONDS = 19_800; // +05:30
const IST_OFFSET_MS = IST_OFFSET_SECONDS * 1000;
const DAY_MS = 86_400_000;

/** A calendar date as lightweight-charts' BusinessDay (month is 1-12). */
export interface BusinessDay {
  year: number;
  month: number;
  day: number;
}

/** lightweight-charts time: epoch-seconds (intraday, shifted) or a BusinessDay (daily/weekly). */
export type ChartTime = number | BusinessDay;

const INTRADAY: Record<string, number> = {
  '1m': 60_000,
  '5m': 300_000,
  '15m': 900_000,
  '1h': 3_600_000,
};

export function isIntraday(interval: string): boolean {
  return interval in INTRADAY;
}

/** Floor a real epoch-ms instant to its bucket start for the interval (live tick→bar aggregation). */
export function floorBucketMs(epochMs: number, interval: string): number {
  if (isIntraday(interval)) {
    // The market-data caggs bucket on UTC-epoch boundaries. +05:30 (330 min) is a multiple of the
    // 1/5/15-min sizes, so those coincide with IST marks; 1h lands on :30 IST (the documented cagg
    // quirk). Flooring on the UTC-epoch boundary matches the backend buckets for every intraday size.
    const bucket = INTRADAY[interval];
    return Math.floor(epochMs / bucket) * bucket;
  }
  if (interval === '1d') {
    const istWall = epochMs + IST_OFFSET_MS;
    return Math.floor(istWall / DAY_MS) * DAY_MS - IST_OFFSET_MS;
  }
  // 1w → Monday 00:00 IST (epoch day 0 = Thursday)
  const istWall = epochMs + IST_OFFSET_MS;
  const dayIndex = Math.floor(istWall / DAY_MS);
  const dow = (dayIndex + 4) % 7; // 0 = Sunday … 6 = Saturday
  const mondayBack = (dow + 6) % 7;
  return (dayIndex - mondayBack) * DAY_MS - IST_OFFSET_MS;
}

/** Real epoch-ms → the lightweight-charts time for the interval. */
export function toChartTime(epochMs: number, interval: string): ChartTime {
  if (isIntraday(interval)) {
    return Math.floor(epochMs / 1000) + IST_OFFSET_SECONDS;
  }
  const d = new Date(epochMs + IST_OFFSET_MS);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}

/**
 * Inverse of {@link toChartTime}: a chart time read back from LWC → real epoch-ms. Accepts the
 * `'yyyy-mm-dd'` BusinessDay string form lightweight-charts also emits.
 */
export function fromChartTime(time: ChartTime | string): number {
  if (typeof time === 'string') {
    const [y, m, d] = time.split('-').map(Number);
    return Date.UTC(y, m - 1, d) - IST_OFFSET_MS;
  }
  if (typeof time === 'number') {
    return (time - IST_OFFSET_SECONDS) * 1000;
  }
  return Date.UTC(time.year, time.month - 1, time.day) - IST_OFFSET_MS;
}

/** Convenience: an ISO string (the candle/tick bucket) → the chart time for the interval. */
export function isoToChartTime(iso: string, interval: string): ChartTime {
  return toChartTime(Date.parse(iso), interval);
}

import { compareDecimal } from '../../../core/decimal';
import { floorBucketMs } from './timestamp';

/**
 * Library-agnostic datafeed core (E-10.1, A13): REST candle access with an internal paging contract,
 * IST bucket flooring, and live tick→bar aggregation — typed against INTERNAL candle DTOs with
 * **zero chart-library imports** (the E-9 boundary). The LwcChartBinding maps this onto
 * lightweight-charts. Refcounted per-symbol live subscriptions are delegated to the tested
 * MarketStore.track/untrack; this core only turns a tick into the in-progress bar.
 */

/** Internal candle DTO — prices stay decimal STRINGS; `time` is the bucket-start epoch-ms (IST instant). */
export interface Bar {
  time: number;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: number;
}

/** Raw candle as the market-data REST surface returns it. */
export interface RawCandle {
  bucket: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: number;
}

/** A tick reduced to what bar aggregation needs. */
export interface TickInput {
  lastPrice: string;
  cumulativeDayVolume: number;
  timestampMs: number;
}

/** Injected candle gateway (HttpClient-backed in the app; a fake in tests). */
export type CandleFetch = (
  exchange: string,
  tradingsymbol: string,
  interval: string,
  fromIso: string,
  toIso: string,
  limit: number,
) => Promise<RawCandle[]>;

const SPAN_MS: Record<string, number> = {
  '1m': 60_000,
  '5m': 300_000,
  '15m': 900_000,
  '1h': 3_600_000,
  '1d': 86_400_000,
  '1w': 604_800_000,
};
// over-fetch slack: intraday loses overnight/weekend bars; daily/weekly lose weekends/holidays
const FUDGE: Record<string, number> = { '1m': 3, '5m': 3, '15m': 3, '1h': 3, '1d': 1.7, '1w': 1.3 };

export class ArthaYantraDatafeed {
  constructor(private readonly fetch: CandleFetch) {}

  private toBar(c: RawCandle): Bar {
    return {
      time: Date.parse(c.bucket),
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
      volume: c.volume,
    };
  }

  /**
   * The internal paging contract (E-10.1, ex-`countBack`): return AT LEAST `count` bars strictly
   * before `toMs`, paging backward as needed, until the series is exhausted. The single most
   * error-prone piece of the v1 adapter.
   */
  async fetchBars(
    exchange: string,
    tradingsymbol: string,
    interval: string,
    toMs: number,
    count: number,
  ): Promise<Bar[]> {
    const span = SPAN_MS[interval] ?? SPAN_MS['1d'];
    const fudge = FUDGE[interval] ?? 2;
    let fromMs = toMs - Math.ceil(count * span * fudge);
    let bars: Bar[] = [];
    for (let attempt = 0; attempt < 5; attempt++) {
      const raw = await this.fetch(
        exchange,
        tradingsymbol,
        interval,
        new Date(fromMs).toISOString(),
        new Date(toMs).toISOString(),
        Math.max(count * 4, 200),
      );
      bars = raw.map((c) => this.toBar(c)).filter((b) => b.time < toMs);
      if (bars.length >= count) {
        return bars;
      }
      const widened = fromMs - Math.ceil(count * span * fudge);
      if (widened >= fromMs) {
        break;
      }
      fromMs = widened;
    }
    return bars; // series exhausted — fewer than `count` is all there is
  }

  /**
   * Aggregate a tick into the in-progress bar for `interval`. A tick whose IST bucket differs from
   * the current bar's opens a new bar (rollover); otherwise high/low/close/volume update in place.
   * High/low compared as decimal strings — never parseFloat (E-6).
   */
  aggregate(
    tick: TickInput,
    interval: string,
    current: Bar | null,
  ): { bar: Bar; rollover: boolean } {
    const bucket = floorBucketMs(tick.timestampMs, interval);
    if (!current || floorBucketMs(current.time, interval) !== bucket) {
      return {
        bar: {
          time: bucket,
          open: tick.lastPrice,
          high: tick.lastPrice,
          low: tick.lastPrice,
          close: tick.lastPrice,
          volume: tick.cumulativeDayVolume,
        },
        rollover: current != null,
      };
    }
    const high = compareDecimal(tick.lastPrice, current.high) > 0 ? tick.lastPrice : current.high;
    const low = compareDecimal(tick.lastPrice, current.low) < 0 ? tick.lastPrice : current.low;
    return {
      bar: { ...current, high, low, close: tick.lastPrice, volume: tick.cumulativeDayVolume },
      rollover: false,
    };
  }
}

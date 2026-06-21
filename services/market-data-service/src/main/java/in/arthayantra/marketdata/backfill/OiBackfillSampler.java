package in.arthayantra.marketdata.backfill;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Folds 1-minute OHLC+OI candles into N-minute snapshot buckets for the OI-backfill importer
 * (data-foundation milestone, plan §2). Buckets are floored to the IST-midnight N-minute boundary —
 * the SAME alignment Postgres {@code time_bucket} uses, so a backfilled row at e.g. {@code 09:20:00}
 * falls cleanly into the reader's {@code 09:20} bucket (parity with the live OI readers).
 *
 * <p>Per bucket: OHLC of its 1m bars (open = first bar's open, high/low = extrema, close = last bar's
 * close), the summed bar volume, and the LAST non-null OI in the bucket (the end-of-bucket OI — the
 * documented backfill approximation, since a 1m bar's {@code oi} is the minute's end-of-bar value).
 */
final class OiBackfillSampler {

  /** One sampled bucket: aligned start + OHLC + summed volume + end-of-bucket OI (nullable). */
  record Bucket(
      OffsetDateTime start,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      long volume,
      Long oi) {}

  /** Time-ordered N-minute buckets from 1m candles (empty in → empty out). */
  static List<Bucket> sample(List<Candle> oneMin, int minutes) {
    List<Candle> sorted = new ArrayList<>(oneMin);
    sorted.sort(Comparator.comparing(Candle::bucketStart));
    Map<OffsetDateTime, Acc> byBucket = new TreeMap<>();
    for (Candle c : sorted) {
      byBucket.computeIfAbsent(bucketStart(c.bucketStart(), minutes), k -> new Acc()).fold(c);
    }
    List<Bucket> out = new ArrayList<>(byBucket.size());
    byBucket.forEach(
        (start, a) -> out.add(new Bucket(start, a.open, a.high, a.low, a.close, a.volume, a.oi)));
    return out;
  }

  /** Floors an IST timestamp to the N-minute boundary from IST midnight (pg time_bucket parity). */
  static OffsetDateTime bucketStart(OffsetDateTime time, int minutes) {
    OffsetDateTime ist = time.withOffsetSameInstant(Ist.OFFSET);
    LocalDate day = ist.toLocalDate();
    int minsSinceMidnight = ist.getHour() * 60 + ist.getMinute();
    int floored = (minsSinceMidnight / minutes) * minutes;
    return day.atStartOfDay().plusMinutes(floored).atOffset(Ist.OFFSET);
  }

  /** Mutable OHLCV+OI accumulator for one bucket (1m bars folded in time order). */
  private static final class Acc {
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;
    private Long oi;

    void fold(Candle c) {
      if (open == null) {
        open = c.open();
        high = c.high();
        low = c.low();
      } else {
        high = high.max(c.high());
        low = low.min(c.low());
      }
      close = c.close();
      volume += c.volume();
      if (c.oi() != null) {
        oi = c.oi(); // last (end-of-bucket) OI
      }
    }
  }

  private OiBackfillSampler() {}
}

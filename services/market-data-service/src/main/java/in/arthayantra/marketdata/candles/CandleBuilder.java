package in.arthayantra.marketdata.candles;

import in.arthayantra.marketdata.feed.NormalizedTick;
import in.arthayantra.marketdata.feed.NormalizedTickListener;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single-writer 1m candle builder (Phase 10 / B-6 candle math): minute-boundary close with a
 * 5-second late-tick grace; duplicate seqs dropped; per-bar volume = cumulative-day-volume delta
 * (bucket end minus bucket start, reset-aware) so replays can never double-count; OI carried for
 * F&O. Bars close either when a newer-bucket tick arrives or when the {@link #flush} sweep passes
 * grace; closed bars go to the {@link BarSink} (persist + publish).
 */
public class CandleBuilder implements NormalizedTickListener {

  /** Where closed bars land (JDBC + Redis in production, a recorder in tests). */
  public interface BarSink {
    /** One closed 1m bar. */
    void onClosedBar(Candle bar);
  }

  private static final Logger log = LoggerFactory.getLogger(CandleBuilder.class);

  static final Duration LATE_TICK_GRACE = Duration.ofSeconds(5);
  // P1 (2026-07-03): how far a tick's bucket may sit AHEAD of the wall clock before the tick is
  // rejected as mis-stamped. Generous vs feed-host clock skew (a real print's minute bucket starts
  // at/before "now"), tiny vs the observed poison (a ≥15:30-stamped print clamping into the 15:29
  // close bucket at 12:40 — 2h49m ahead).
  static final Duration FUTURE_TICK_ALLOWANCE = Duration.ofSeconds(60);

  private final SessionBucketer bucketer;
  private final BarSink sink;
  private final Clock clock;
  private final String source;
  private final Map<String, Accumulator> accumulators = new ConcurrentHashMap<>();

  private static final class Accumulator {
    OffsetDateTime bucket;
    OffsetDateTime lastClosedBucket;
    BigDecimal open;
    BigDecimal high;
    BigDecimal low;
    BigDecimal close;
    long lastSeq = Long.MIN_VALUE;
    long cumVolumeAtBarStart;
    long cumVolumeLatest;
    Long oi;
  }

  /** Wires the builder; {@code source} is MOCK under the mock profile, TICK_AGG live. */
  public CandleBuilder(SessionBucketer bucketer, BarSink sink, Clock clock, String source) {
    this.bucketer = bucketer;
    this.sink = sink;
    this.clock = clock;
    this.source = source;
  }

  @Override
  public void onNormalizedTick(NormalizedTick tick) {
    if (bucketer.isPreOpen(tick.timestamp())) {
      // pre-open order-collection prints are indicative equilibrium values, not trades —
      // folding them into the 09:15 bucket corrupted the open bar's range (audit 2026-07-02 §5)
      return;
    }
    String key = tick.exchange() + ":" + tick.tradingsymbol();
    Accumulator acc = accumulators.computeIfAbsent(key, k -> new Accumulator());
    synchronized (acc) {
      if (tick.seq() <= acc.lastSeq) {
        return; // duplicate / reconnect-overlap (B-6: drop seq <= lastSeen)
      }
      OffsetDateTime bucket = bucketer.bucketFor(tick.timestamp());
      // P1 REGRESSION (2026-07-03, NIFTY26JULFUT): ONE mis-stamped print whose clamped bucket sat
      // hours ahead of the wall clock opened a far-future bar; every real tick for the rest of the
      // session then dropped silently as "out-of-order" and the signal engine starved while ticks
      // kept flowing. A bucket meaningfully ahead of "now" cannot be a real print — drop the TICK
      // before it touches any accumulator state (not even lastSeq: a later replay must not be
      // shadowed by a rogue frame).
      if (bucket.toInstant().isAfter(clock.instant().plus(FUTURE_TICK_ALLOWANCE))) {
        log.warn(
            "dropping future-stamped tick {} ts={} bucket={} now={} — mis-stamped frame, "
                + "would poison the 1m stream",
            key, tick.timestamp(), bucket, clock.instant());
        return;
      }
      // a flushed bar is CLOSED FOREVER (B-7 closed-bars-immutable): post-close prints clamp
      // into the 15:29 bucket all evening — re-opening it would clobber the true session close
      if (acc.lastClosedBucket != null && !bucket.isAfter(acc.lastClosedBucket)) {
        acc.lastSeq = tick.seq();
        return;
      }
      if (acc.bucket == null) {
        startBar(acc, tick, bucket);
      } else if (bucket.isAfter(acc.bucket)) {
        closeBar(tick.exchange(), tick.tradingsymbol(), acc);
        startBar(acc, tick, bucket);
      } else if (bucket.isBefore(acc.bucket)) {
        // out-of-order into an already-advanced bucket: late beyond grace — ignore
        acc.lastSeq = tick.seq();
        return;
      } else {
        acc.high = acc.high.max(tick.lastPrice());
        acc.low = acc.low.min(tick.lastPrice());
        acc.close = tick.lastPrice(); // highest-seq tick (guarded above)
        acc.cumVolumeLatest = tick.cumulativeDayVolume();
        acc.oi = tick.openInterest() != null ? tick.openInterest() : acc.oi;
      }
      acc.lastSeq = tick.seq();
    }
  }

  private void startBar(Accumulator acc, NormalizedTick tick, OffsetDateTime bucket) {
    // cumulative-delta baseline: previous bar's end. A DROP means the exchange day rolled (Kite
    // resets cumulative volume) — baseline at the tick's OWN cumulative, the same semantics as the
    // very first tick ever seen (volume counts increments observed after it). T23 (2026-07-25):
    // the old zero baseline folded the entire pre-open auction quantity into the first bar a WARM
    // process built after rollover (a cold boot never did — accumulators start empty), inflating
    // the 09:15 tick-agg bar by the auction volume (+94 lots on 2026-07-24) and tripping the
    // partial-bucket canary every warm morning. Cost: the rollover tick's own intra-minute delta
    // is unobservable and uncounted — identical to the cold-boot case, bounded to one tick.
    long baseline = acc.lastSeq == Long.MIN_VALUE ? tick.cumulativeDayVolume() : acc.cumVolumeLatest;
    if (tick.cumulativeDayVolume() < baseline) {
      baseline = tick.cumulativeDayVolume();
    }
    acc.bucket = bucket;
    acc.open = tick.lastPrice();
    acc.high = tick.lastPrice();
    acc.low = tick.lastPrice();
    acc.close = tick.lastPrice();
    acc.cumVolumeAtBarStart = baseline;
    acc.cumVolumeLatest = tick.cumulativeDayVolume();
    acc.oi = tick.openInterest();
  }

  private void closeBar(String exchange, String tradingsymbol, Accumulator acc) {
    long volume = Math.max(0, acc.cumVolumeLatest - acc.cumVolumeAtBarStart);
    sink.onClosedBar(
        new Candle(
            exchange, tradingsymbol, "1m", acc.bucket,
            acc.open, acc.high, acc.low, acc.close, volume, acc.oi, source));
    acc.lastClosedBucket = acc.bucket;
  }

  /** Closes every bar whose minute has passed the late-tick grace (scheduled sweep). */
  public int flush() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
    accumulators.forEach(
        (key, acc) -> {
          synchronized (acc) {
            if (acc.bucket != null
                && now.isAfter(acc.bucket.plusMinutes(1).plus(LATE_TICK_GRACE))) {
              String[] parts = key.split(":", 2);
              closeBar(parts[0], parts[1], acc);
              acc.cumVolumeAtBarStart = acc.cumVolumeLatest;
              acc.bucket = null;
              closed.incrementAndGet();
            }
          }
        });
    return closed.get();
  }
}

package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Live guard for the audit B1 / FID P0-1 done-check ("live 3m bar volume vs 1m-sum for the same
 * bucket"): the {@link LiveSeriesStore} completeness filter now excludes in-progress buckets so a
 * frozen first-minute partial can no longer accrue — this canary is the standing detector that the
 * regression stays fixed. For every warmed 3m series it re-derives the LAST COMPLETED 3m bar's
 * volume from the three 1m bars of the same bucket already held in the store's 1m series and
 * compares. A frozen 3m partial carries only its first minute's volume (~1/3 of the true bucket),
 * so a persistent volume shortfall is the fingerprint of the bug reappearing. Both series are in
 * memory — no REST call, no DB read, no new endpoint.
 *
 * <p>Purely observational: on a mismatch it increments {@code ay_signal_partial_bucket_mismatch_total}
 * and logs once per (series, bucket); it never blocks or mutates a series. It skips silently when the
 * three 1m bars are not all present (a coverage gap, not a freeze — {@code volume-tolerance} does not
 * apply to that case) or when the newest 3m bucket is still forming.
 *
 * <p>The comparison is structurally UNLIKE-vs-UNLIKE (T23, 2026-07-25): the 3m side is a REST-pulled
 * SQL rollup of DB 1m rows that the 10-minute recency window authoritatively REPLACES with
 * broker-official Kite bars at every boundary, while the store's 1m side stays live tick-agg and is
 * never revised. A tick printing in the sub-second gap between the last ~1 Hz snapshot before a 3m
 * edge and the edge itself is credited to the earlier bucket by the broker and the later one by
 * tick-agg, so consecutive buckets carry equal-and-opposite lot-multiple skews (measured ≤8 lots =
 * 520 on 35 of 37 events, 2026-07-24). A mismatch is treated as benign only when it clears BOTH
 * gates: at most the absolute tolerance — {@code max(volume-tolerance,
 * volume-tolerance-pct% of the expected 1m sum)}, default floor 650 = 10 NIFTY lots — AND at most
 * 10% of the expected 1m sum. The relative gate keeps a frozen partial on a genuinely THIN bucket
 * (e.g. 400 frozen of a true 1,000) firing even though its absolute shortfall is small. The frozen
 * first-minute signature (~2/3 of the bucket missing) fails both gates on any bar. Both knobs are
 * live-tunable: {@code artha.signals.partial-bucket-canary.volume-tolerance} (floor) +
 * {@code ...volume-tolerance-pct} (scaling).
 *
 * <p>⚠️ <b>{@code volume-tolerance-pct} DEFAULTS TO 0 — the scaling mechanism ships DORMANT, and
 * raising it is not safe yet</b> (G9/T23, 2026-07-29). The residue IS proportional (2.4–3.7% of a
 * thick opening bar) so scaling looks right, but the benign straddle arrives as an equal-and-opposite
 * PAIR across consecutive buckets whose members differ hugely as a FRACTION of their own bucket: the
 * 07-29 ±16,835 pair is 3.7% of the 460,005 opening bucket but 11.9% of the 141,245 next one. Any
 * pct>0 large enough to quiet the thick half leaves the thin half WARNing, converting a benign PAIRED
 * event into an <b>unpaired</b> one — precisely the signature the runbook (signal-analysis README
 * §3.17) teaches operators to read as a real defect, with its corroborating partner now suppressed.
 * At pct=0 the predicate is byte-identical to the pre-G9 fixed-absolute gate (pinned by test), so no
 * event changes state. Do NOT raise this until pair-aware suppression exists — suppression must key
 * on the ± partner, not on either bucket's size alone.
 *
 * <p>Depends ONLY on {@link LiveSeriesStore} (never {@link SignalEngine}) and shares the engine's
 * on/off gate ({@code artha.signals.engine-enabled}, default on): the 3m series only exist when the
 * live engine warms them, so this is meaningless — and would just churn — when the engine is disabled
 * (e.g. the paper integration tests). Gating on the same property keeps it out of that context.
 */
@Component
@ConditionalOnProperty(
    value = "artha.signals.engine-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PartialBucketCanary {

  private static final Logger log = LoggerFactory.getLogger(PartialBucketCanary.class);
  private static final String THREE_MINUTE = "3m";
  private static final String ONE_MINUTE = "1m";
  private static final Duration BUCKET = Duration.ofMinutes(3);

  private final LiveSeriesStore store;
  private final Clock clock;
  private final Counter mismatches;
  private final long volumeTolerance;
  private final double volumeTolerancePct;
  // Once per (series, bucket): the last completed 3m bucket already flagged for each 3m series.
  private final Map<SeriesKey, Instant> flagged = new ConcurrentHashMap<>();

  /**
   * Wires the shared series store, the clock, the meter and the (tunable) absolute-arm basis: a
   * fixed floor plus a percent-of-expected-sum scale (0 = the pre-G9 fixed-absolute gate).
   */
  public PartialBucketCanary(
      LiveSeriesStore store,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${artha.signals.partial-bucket-canary.volume-tolerance:650}") long volumeTolerance,
      @Value("${artha.signals.partial-bucket-canary.volume-tolerance-pct:0.0}")
          double volumeTolerancePct) {
    this.store = store;
    this.clock = clock;
    this.mismatches = meterRegistry.counter("ay_signal_partial_bucket_mismatch_total");
    this.volumeTolerance = volumeTolerance;
    this.volumeTolerancePct = volumeTolerancePct;
  }

  /** Sweeps every warmed 3m series, comparing its last completed bar to the 1m sum. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 90_000, scheduler = "monitorTaskScheduler")
  public void sweep() {
    Instant now = clock.instant();
    for (SeriesKey key : store.keys()) {
      if (!THREE_MINUTE.equals(key.interval())) {
        continue;
      }
      try {
        check(key, now);
      } catch (RuntimeException e) {
        // Contained PER KEY: this canary is the first cross-thread reader of EngineSeries (an
        // unsynchronized ArrayList mutated on the eval thread), so a torn read on one series must
        // skip only that series, never abort the whole sweep cycle.
        log.warn("partial-bucket canary check failed for {}: {}", key.canonical(), e.toString());
      }
    }
  }

  private void check(SeriesKey threeMinKey, Instant now) {
    EngineSeries threeMin = store.series(threeMinKey);
    if (threeMin == null || threeMin.size() == 0) {
      return;
    }
    EngineCandle last = threeMin.candle(threeMin.size() - 1);
    Instant bucketStart = last.bucketStart().toInstant();
    if (bucketStart.plus(BUCKET).isAfter(now)) {
      return; // the newest 3m bar is still forming — nothing complete to check yet
    }
    EngineSeries oneMin =
        store.series(new SeriesKey(threeMinKey.exchange(), threeMinKey.tradingsymbol(), ONE_MINUTE));
    if (oneMin == null) {
      return;
    }
    long expected = 0;
    for (int minute = 0; minute < 3; minute++) {
      Instant minuteStart = bucketStart.plus(Duration.ofMinutes(minute));
      int index = oneMin.indexAtOrBefore(minuteStart);
      if (index < 0 || !oneMin.candle(index).bucketStart().toInstant().equals(minuteStart)) {
        return; // 1m coverage incomplete for this bucket — skip silently (not a freeze)
      }
      expected += oneMin.candle(index).volume();
    }
    long actual = last.volume();
    long diff = Math.abs(actual - expected);
    // benign needs BOTH gates: ≤ the absolute arm (a fixed floor, optionally scaled by a percent
    // of the expected sum — DORMANT at the shipped pct=0, where this is exactly the pre-G9 fixed
    // gate; the class javadoc explains why pct>0 manufactures false-unpaired events) AND ≤ 10% of
    // the expected sum. The relative gate keeps a frozen partial on a thin bucket firing (its
    // absolute shortfall is small but its relative one is ~2/3); a zero-volume expected sum
    // tolerates only an exact match.
    long absoluteTolerance =
        Math.max(volumeTolerance, (long) (expected * volumeTolerancePct / 100.0));
    if (diff <= absoluteTolerance && diff * 10L <= expected) {
      flagged.remove(threeMinKey); // healthy for this series — allow the next bucket to re-flag
      return;
    }
    if (bucketStart.equals(flagged.get(threeMinKey))) {
      return; // already flagged this exact (series, bucket)
    }
    flagged.put(threeMinKey, bucketStart);
    mismatches.increment();
    log.warn(
        "partial-bucket canary: {} last completed 3m bar volume {} != Σ(3×1m) {} for bucket {} "
            + "(shortfall {}) — a frozen first-minute partial (audit B1 / FID P0-1) looks exactly like this",
        threeMinKey.canonical(), actual, expected, bucketStart, expected - actual);
  }
}

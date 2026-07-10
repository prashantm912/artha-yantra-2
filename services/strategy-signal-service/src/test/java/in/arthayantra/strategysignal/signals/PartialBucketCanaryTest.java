package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The audit B1 / FID P0-1 live done-check as a unit test: the canary compares each completed 3m
 * bar's volume against the sum of the three 1m bars of the same bucket, all held in the store.
 */
class PartialBucketCanaryTest {

  private static final SeriesKey THREE_MIN = new SeriesKey("NFO", "NIFTY26JULFUT", "3m");
  private static final SeriesKey ONE_MIN = new SeriesKey("NFO", "NIFTY26JULFUT", "1m");
  private static final String COUNTER = "ay_signal_partial_bucket_mismatch_total";

  // 09:19 IST: the 09:15–09:18 3m bucket is complete; 09:18 onward is still forming.
  private static final Clock CLOCK =
      Clock.fixed(OffsetDateTime.parse("2026-07-03T09:19:00+05:30").toInstant(), ZoneOffset.UTC);

  private static EngineCandle bar(String iso, long volume) {
    return new EngineCandle(
        OffsetDateTime.parse(iso), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100.5"), volume, null);
  }

  private static PartialBucketCanary canary(LiveSeriesStore store, MeterRegistry registry) {
    return new PartialBucketCanary(store, CLOCK, registry, 0L);
  }

  @Test
  void healthyBucketWhere3mVolumeEqualsThe1mSumDoesNotFire() {
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 100));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 100));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 100));
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 300));
    MeterRegistry registry = new SimpleMeterRegistry();

    canary(store, registry).sweep();

    assertThat(registry.counter(COUNTER).count()).isZero();
  }

  @Test
  void frozenPartial3mBarIsFlaggedOncePerBucket() {
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 100));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 100));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 100));
    // the regression: the 3m bar frozen at its FIRST minute's volume (100), not the true 300.
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 100));
    MeterRegistry registry = new SimpleMeterRegistry();
    PartialBucketCanary c = canary(store, registry);

    c.sweep();
    c.sweep(); // same (series, bucket) — must not double-count

    assertThat(registry.counter(COUNTER).count()).isEqualTo(1.0);
  }

  @Test
  void incompleteOneMinuteCoverageIsSkippedSilently() {
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 100));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 100)); // 09:17 1m bar is missing
    // volume would mismatch IF checked — but the coverage gap means "not a freeze", so skip.
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 100));
    MeterRegistry registry = new SimpleMeterRegistry();

    canary(store, registry).sweep();

    assertThat(registry.counter(COUNTER).count()).isZero();
  }

  @Test
  void anInProgress3mBucketIsNotCheckedYet() {
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    // the 09:18 bucket completes at 09:21 — still forming at the 09:19 clock, so it is not compared
    // even though no 1m bars back it (a comparison would otherwise skip on coverage anyway).
    store.append(THREE_MIN, bar("2026-07-03T09:18:00+05:30", 50));
    MeterRegistry registry = new SimpleMeterRegistry();

    canary(store, registry).sweep();

    assertThat(registry.counter(COUNTER).count()).isZero();
  }
}

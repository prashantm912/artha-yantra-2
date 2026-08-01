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

  // pct 0 = the pre-G9 fixed-absolute gate; the scaled-basis tests pass the shipped 5.0 explicitly.
  private static PartialBucketCanary canary(LiveSeriesStore store, MeterRegistry registry) {
    return new PartialBucketCanary(store, CLOCK, registry, 0L, 0.0);
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
  void boundaryStraddleResidueWithinToleranceDoesNotFireButAFrozenPartialStillDoes() {
    // T23 (2026-07-25): the 3m side is broker-replaced DB data while the 1m side stays tick-agg, so
    // a boundary-straddling tick leaves an equal-and-opposite lot-multiple skew on consecutive
    // buckets (≤8 lots = 520 on 35 of 37 measured events). The shipped default tolerance (650 = 10
    // NIFTY lots) must absorb that residue while a frozen first-minute partial (~2/3 of the bucket
    // missing) must still fire.
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 5_000));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 5_000));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 5_000));
    // tick-agg sum 15,000 vs broker-official 14,480: a 520 (8-lot) boundary straddle — benign.
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 14_480));
    MeterRegistry registry = new SimpleMeterRegistry();
    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0).sweep();
    assertThat(registry.counter(COUNTER).count()).as("8-lot residue absorbed").isZero();

    LiveSeriesStore frozen = new LiveSeriesStore(null, CLOCK);
    frozen.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 5_000));
    frozen.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 5_000));
    frozen.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 5_000));
    // the real regression: the 3m bar frozen at its first minute — shortfall 10,000 >> 650.
    frozen.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 5_000));
    MeterRegistry frozenRegistry = new SimpleMeterRegistry();
    new PartialBucketCanary(frozen, CLOCK, frozenRegistry, 650L, 5.0).sweep();
    assertThat(frozenRegistry.counter(COUNTER).count()).as("frozen partial still fires").isEqualTo(1.0);
  }

  @Test
  void frozenPartialOnAThinBucketStillFiresDespiteASmallAbsoluteShortfall() {
    // codex review (B2 round 2): 400+300+300 with the 3m bar frozen at its first minute (400) — the
    // 600 shortfall sits UNDER the 650 absolute tolerance but is 60% of the expected sum; the
    // relative gate (≤10% of expected) must keep the thin frozen bar firing.
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 400));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 300));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 300));
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 400));
    MeterRegistry registry = new SimpleMeterRegistry();

    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0).sweep();

    assertThat(registry.counter(COUNTER).count()).as("thin frozen bar not masked").isEqualTo(1.0);
  }

  @Test
  void thickOpeningBarBoundaryStraddleResidueDoesNotFire() {
    // G9/T23 (2026-07-29, cleanest session in the series): 6 WARNs, all exact ± pairs on
    // consecutive buckets, largest 16,835 = 259 lots = 3.7% of the 09:15 opening bucket's 460,005
    // 1m sum (broker-official 3m bar 476,840) — provably benign boundary straddle, yet it alarmed
    // because the absolute arm was a fixed 650. The absolute arm's basis scales with bar size so
    // this shape stays quiet.
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 153_335));
    // tick-agg sum 460,005 vs broker-official 476,840: |diff| 16,835 (3.7% of expected).
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 476_840));
    MeterRegistry registry = new SimpleMeterRegistry();

    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0).sweep();

    assertThat(registry.counter(COUNTER).count()).as("thick-bar straddle absorbed").isZero();
  }

  @Test
  void pctZeroRestoresTheFixedAbsoluteGateAndTheThickBarStraddleFiresAgain() {
    // The escape hatch pin: volume-tolerance-pct 0 must reproduce the pre-G9 decision function
    // exactly — the same 07-29 opening-bucket shape that the scaled basis absorbs fires again.
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 153_335));
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 476_840));
    MeterRegistry registry = new SimpleMeterRegistry();

    new PartialBucketCanary(store, CLOCK, registry, 650L, 0.0).sweep();

    assertThat(registry.counter(COUNTER).count()).as("pct=0 = fixed gate").isEqualTo(1.0);
  }

  @Test
  void aThinBarWithTheSameAbsoluteDivergenceStillFires() {
    // The scaled basis must never buy a thin bar extra headroom: the same 16,835 divergence on a
    // 300-volume bucket is a gross defect — max(650, 5% of 300 = 15) stays 650, and the relative
    // arm fails too, so it fires.
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 100));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 100));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 100));
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 17_135));
    MeterRegistry registry = new SimpleMeterRegistry();

    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0).sweep();

    assertThat(registry.counter(COUNTER).count()).as("thin bar keeps the floor").isEqualTo(1.0);
  }

  @Test
  void frozenPartialOnAThickBarStillFiresUnderTheScaledBasis() {
    // The scaling must never absorb the actual regression signature on the bars it relaxes: a 3m
    // bar frozen at its first minute of the 07-29-sized bucket is a ~66% shortfall — far beyond
    // both the 5% scaled absolute arm (23,000) and the 10% relative arm.
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 153_335));
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 153_335));
    MeterRegistry registry = new SimpleMeterRegistry();

    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0).sweep();

    assertThat(registry.counter(COUNTER).count()).as("thick frozen partial fires").isEqualTo(1.0);
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

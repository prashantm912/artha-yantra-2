package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.signals.PartialBucketCanary.LotSizes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.web.client.RestClient;

/**
 * The audit B1 / FID P0-1 live done-check as a unit test: the canary compares each completed 3m
 * bar's volume against the sum of the three 1m bars of the same bucket, all held in the store —
 * plus the G9/T23 pair awareness that recognises the documented boundary straddle (signal-analysis
 * README §3.17) as ONE benign unit spanning two consecutive buckets.
 */
class PartialBucketCanaryTest {

  private static final SeriesKey THREE_MIN = new SeriesKey("NFO", "NIFTY26JULFUT", "3m");
  private static final SeriesKey ONE_MIN = new SeriesKey("NFO", "NIFTY26JULFUT", "1m");
  private static final String COUNTER = "ay_signal_partial_bucket_mismatch_total";
  private static final String STRADDLES = "ay_signal_partial_bucket_straddle_total";

  // The live NIFTY lot at the time of the 2026-07-29 observation. Production resolves it per
  // contract from the instrument master; the tests pin one value so the arithmetic is checkable.
  private static final LotSizes NIFTY_LOT = (exchange, tradingsymbol) -> 65L;
  private static final LotSizes UNKNOWN_LOT = (exchange, tradingsymbol) -> null;

  // No durable store: `save` refuses, so the canary never defers and behaves exactly as it did
  // before G9. This is a real production configuration (no Redis bean / Redis down), and it is what
  // the pre-existing single-sweep tolerance tests below assert against, unchanged.
  private static final PartialBucketCanary.HeldHalves NO_STORE =
      new PartialBucketCanary.HeldHalves() {
        @Override
        public boolean save(SeriesKey key, PartialBucketCanary.Held half) {
          return false;
        }

        @Override
        public Optional<Map<String, PartialBucketCanary.Held>> carried() {
          return Optional.of(Map.of());
        }

        @Override
        public void clear(String seriesId) {
          // nothing stored
        }
      };

  /**
   * A store whose READS fail while writes succeed — a blip between the restore and the save, which
   * is the realistic transient. Writes must succeed here or the test cannot isolate the read path:
   * a store that also refused to save would WARN for that reason instead.
   */
  private static final PartialBucketCanary.HeldHalves UNREADABLE_STORE =
      new PartialBucketCanary.HeldHalves() {
        @Override
        public boolean save(SeriesKey key, PartialBucketCanary.Held half) {
          return true;
        }

        @Override
        public Optional<Map<String, PartialBucketCanary.Held>> carried() {
          return Optional.empty(); // UNKNOWN, never "nothing carried"
        }

        @Override
        public void clear(String seriesId) {
          // unreachable
        }
      };

  /** A durable in-memory stand-in for the Redis-backed store; survives a canary instance. */
  private static PartialBucketCanary.HeldHalves store() {
    Map<String, PartialBucketCanary.Held> backing = new LinkedHashMap<>();
    return new PartialBucketCanary.HeldHalves() {
      @Override
      public boolean save(SeriesKey key, PartialBucketCanary.Held half) {
        backing.put(key.canonical(), half);
        return true;
      }

      @Override
      public Optional<Map<String, PartialBucketCanary.Held>> carried() {
        return Optional.of(new LinkedHashMap<>(backing));
      }

      @Override
      public void clear(String seriesId) {
        backing.remove(seriesId);
      }
    };
  }

  // 09:19 IST: the 09:15–09:18 3m bucket is complete; 09:18 onward is still forming.
  private static final Clock CLOCK =
      Clock.fixed(OffsetDateTime.parse("2026-07-03T09:19:00+05:30").toInstant(), ZoneOffset.UTC);

  /** A clock the pair tests advance bucket by bucket (a straddle partner arrives 3m later). */
  private static final class MovableClock extends Clock {
    private Instant now;

    MovableClock(Instant start) {
      this.now = start;
    }

    void set(Instant at) {
      this.now = at;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static EngineCandle bar(String iso, long volume) {
    return bar(OffsetDateTime.parse(iso), volume);
  }

  private static EngineCandle bar(OffsetDateTime at, long volume) {
    return new EngineCandle(
        at, new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100.5"), volume, null);
  }

  // pct 0 = the pre-G9 fixed-absolute gate; the scaled-basis tests pass the shipped 5.0 explicitly.
  private static PartialBucketCanary canary(LiveSeriesStore store, MeterRegistry registry) {
    return new PartialBucketCanary(store, CLOCK, registry, 0L, 0.0, NIFTY_LOT, NO_STORE);
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
    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0, NIFTY_LOT, NO_STORE).sweep();
    assertThat(registry.counter(COUNTER).count()).as("8-lot residue absorbed").isZero();

    LiveSeriesStore frozen = new LiveSeriesStore(null, CLOCK);
    frozen.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 5_000));
    frozen.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 5_000));
    frozen.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 5_000));
    // the real regression: the 3m bar frozen at its first minute — shortfall 10,000 >> 650.
    frozen.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 5_000));
    MeterRegistry frozenRegistry = new SimpleMeterRegistry();
    new PartialBucketCanary(frozen, CLOCK, frozenRegistry, 650L, 5.0, NIFTY_LOT, NO_STORE).sweep();
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

    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0, NIFTY_LOT, NO_STORE).sweep();

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

    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0, NIFTY_LOT, NO_STORE).sweep();

    assertThat(registry.counter(COUNTER).count()).as("thick-bar straddle absorbed").isZero();
  }

  @Test
  void pctZeroRestoresTheFixedAbsoluteGateAndTheThickBarStraddleFiresAgain() {
    // THE SHIPPED-DEFAULT PATH (G9 review, 2026-07-29): pct defaults to 0, so the live decision
    // function is byte-identical to the pre-G9 fixed-absolute gate and the same 07-29 opening
    // bucket that pct=5 would absorb still fires. The scaling mechanism ships DORMANT because any
    // pct>0 quiets only the thick half of a benign ± pair, manufacturing a false-unpaired event.
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 153_335));
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 476_840));
    MeterRegistry registry = new SimpleMeterRegistry();

    new PartialBucketCanary(store, CLOCK, registry, 650L, 0.0, NIFTY_LOT, NO_STORE).sweep();

    assertThat(registry.counter(COUNTER).count()).as("pct=0 = fixed gate").isEqualTo(1.0);
  }

  @Test
  void theSpringWiredDefaultIsDormantSoLiveBehaviourIsUnchanged() {
    // The explicit-argument tests above cannot see the @Value default, which is what actually
    // ships. Bind the real bean with NO property set: the 07-29 opening bucket must still WARN,
    // proving the shipped default reproduces pre-G9 behaviour rather than silently scaling. (The
    // runner registers no StringRedisTemplate, so there is no durable store and the canary does not
    // defer — which is itself the documented no-Redis production behaviour.)
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 153_335));
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 476_840));
    MeterRegistry registry = new SimpleMeterRegistry();

    canaryContext(store, registry, CLOCK)
        .run(
            context -> {
              context.getBean(PartialBucketCanary.class).sweep();
              assertThat(registry.counter(COUNTER).count())
                  .as("no property set: the shipped default is the pre-G9 fixed gate")
                  .isEqualTo(1.0);
            });
  }

  @Test
  void theScalingKnobBindsFromItsDocumentedPropertyName() {
    // #653-class guard: a knob whose property name does not match its compose/.env passthrough is
    // a silent no-op. Setting the documented key to 5.0 must actually change the decision (the
    // same bucket goes quiet), proving the name in application config reaches this constructor.
    LiveSeriesStore store = new LiveSeriesStore(null, CLOCK);
    store.append(ONE_MIN, bar("2026-07-03T09:15:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:16:00+05:30", 153_335));
    store.append(ONE_MIN, bar("2026-07-03T09:17:00+05:30", 153_335));
    store.append(THREE_MIN, bar("2026-07-03T09:15:00+05:30", 476_840));
    MeterRegistry registry = new SimpleMeterRegistry();

    canaryContext(store, registry, CLOCK)
        .withPropertyValues("artha.signals.partial-bucket-canary.volume-tolerance-pct=5.0")
        .run(
            context -> {
              context.getBean(PartialBucketCanary.class).sweep();
              assertThat(registry.counter(COUNTER).count())
                  .as("the documented property name reaches the constructor")
                  .isZero();
            });
  }

  private static ApplicationContextRunner canaryContext(
      LiveSeriesStore store, MeterRegistry registry, Clock clock) {
    return new ApplicationContextRunner()
        .withUserConfiguration(PlaceholderConfig.class)
        // the Spring-wired constructor resolves lot sizes over REST; these tests point it at a
        // dead port, so the lookup fails soft to null and the straddle rule simply stays closed.
        .withPropertyValues("artha.marketdata.base-url=http://127.0.0.1:1")
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withBean(LiveSeriesStore.class, () -> store)
        .withBean(Clock.class, () -> clock)
        .withBean(MeterRegistry.class, () -> registry)
        .withBean(PartialBucketCanary.class);
  }

  /** Resolves the constructor's {@code @Value} placeholders (and their defaults) in the runner. */
  @Configuration
  static class PlaceholderConfig {
    @Bean
    static PropertySourcesPlaceholderConfigurer placeholders() {
      return new PropertySourcesPlaceholderConfigurer();
    }
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

    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0, NIFTY_LOT, NO_STORE).sweep();

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

    new PartialBucketCanary(store, CLOCK, registry, 650L, 5.0, NIFTY_LOT, NO_STORE).sweep();

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
  // ---------------------------------------------------------------------------------------------
  // G9/T23 pair awareness. The session replayed below is the real one:
  // docs/signal-analysis/2026-07-29-session-findings.md §6.1 — six WARNs on NFO:NIFTY26AUGFUT@3m,
  // every one an exact ± pair on consecutive buckets, every magnitude an exact ×65 lot multiple,
  // zero unpaired events. Under the pre-G9 per-event gate all six alarmed (red-proofed at 6.0).
  // ---------------------------------------------------------------------------------------------

  private static final OffsetDateTime OPEN = OffsetDateTime.parse("2026-07-29T09:15:00+05:30");

  /** One replayed session: a movable clock, a cold store and a canary at the shipped tolerance. */
  private record Replay(
      MovableClock clock,
      LiveSeriesStore store,
      MeterRegistry registry,
      PartialBucketCanary canary) {

    static Replay start(LotSizes lotSizes) {
      return start(lotSizes, PartialBucketCanaryTest.store());
    }

    static Replay start(LotSizes lotSizes, PartialBucketCanary.HeldHalves halves) {
      MovableClock clock = new MovableClock(OPEN.toInstant());
      LiveSeriesStore store = new LiveSeriesStore(null, clock);
      MeterRegistry registry = new SimpleMeterRegistry();
      return new Replay(
          clock,
          store,
          registry,
          new PartialBucketCanary(store, clock, registry, 650L, 0.0, lotSizes, halves));
    }

    /** Appends one 3m bucket + its three 1m bars, then sweeps just after the bucket completes. */
    Replay bucket(int minutesFromOpen, long threeMinute, long m0, long m1, long m2) {
      OffsetDateTime at = OPEN.plusMinutes(minutesFromOpen);
      store.append(ONE_MIN, bar(at, m0));
      store.append(ONE_MIN, bar(at.plusMinutes(1), m1));
      store.append(ONE_MIN, bar(at.plusMinutes(2), m2));
      store.append(THREE_MIN, bar(at, threeMinute));
      return sweepAt(minutesFromOpen + 3, 30);
    }

    /** Sweeps at {@code OPEN + minutes + seconds} without adding any new bar. */
    Replay sweepAt(int minutes, int seconds) {
      clock.set(OPEN.toInstant().plus(Duration.ofMinutes(minutes)).plus(Duration.ofSeconds(seconds)));
      canary.sweep();
      return this;
    }

    double warns() {
      return registry.counter(COUNTER).count();
    }

    double straddles() {
      return registry.counter(STRADDLES).count();
    }
  }

  @Test
  void theSixLiveStraddleHalvesOf20260729AreSuppressedAsThreePairs() {
    Replay session =
        Replay.start(NIFTY_LOT)
            .bucket(0, 476_840, 153_335, 153_335, 153_335) //  sum 460,005 -> +16,835 (259 lots)
            .bucket(3, 124_410, 47_081, 47_082, 47_082) //     sum 141,245 -> -16,835 (259 lots)
            .bucket(6, 90_000, 30_000, 30_000, 30_000) //      healthy
            .bucket(9, 64_025, 20_215, 20_215, 20_215) //      sum  60,645 ->  +3,380 (52 lots)
            .bucket(12, 53_040, 18_806, 18_807, 18_807) //     sum  56,420 ->  -3,380 (52 lots)
            .bucket(15, 90_000, 30_000, 30_000, 30_000) //     healthy
            .bucket(18, 46_995, 13_823, 13_823, 13_824) //     sum  41,470 ->  +5,525 (85 lots)
            .bucket(21, 52_325, 19_348, 19_348, 19_349); //    sum  58,045 ->  -5,720 (88 lots)

    assertThat(session.warns()).as("every event is half of a pair — none is a defect").isZero();
    assertThat(session.straddles()).as("three straddles, reported once each").isEqualTo(3.0);

    session.sweepAt(60, 0); // and no later sweep resurrects them — nothing is left pending
    assertThat(session.warns()).isZero();
  }

  @Test
  void anUnpairedShortfallOfTheSameMagnitudeStillWarns() {
    // the SAME 259-lot magnitude as the 07-29 opening bucket, but the next bucket is clean: the
    // volume really went missing. This is the defect signature the canary exists for.
    Replay session =
        Replay.start(NIFTY_LOT)
            .bucket(0, 476_840, 153_335, 153_335, 153_335) // +16,835, held for corroboration
            .bucket(3, 141_245, 47_081, 47_082, 47_082); //   healthy — no partner arrives

    assertThat(session.warns()).as("an unpaired 259-lot shortfall is reported").isEqualTo(1.0);
    assertThat(session.straddles()).isZero();
  }

  @Test
  void aStraddleShapedHalfWhosePartnerNeverArrivesStillWarns() {
    // the 07-29 opening half alone: plausible in shape, so it IS deferred — but no partner bucket
    // ever completes (session end / feed stop), and the wall-clock deadline must release it.
    // 09:15 + 2x3m + one sweep of grace = 09:22.
    Replay session = Replay.start(NIFTY_LOT).bucket(0, 476_840, 153_335, 153_335, 153_335);
    assertThat(session.warns()).as("deferred while a partner could still arrive").isZero();

    session.sweepAt(6, 30); // 09:21:30 — the partner window has not closed yet
    assertThat(session.warns()).isZero();

    session.sweepAt(7, 1); // 09:22:01 — closed; a missing partner is a real defect
    assertThat(session.warns()).as("a partner that never arrives still WARNs").isEqualTo(1.0);
    assertThat(session.straddles()).isZero();
  }

  @Test
  void aFrozenPartialIsNeverDeferredBecauseItCannotBeAStraddleHalf() {
    // 2/3 of the bucket missing is structural, not a sub-second boundary effect, so the deferral
    // never touches the signature this canary exists for — even though 130,000 is an exact 2,000
    // lots. One sweep, one WARN, exactly the pre-G9 latency.
    Replay session = Replay.start(NIFTY_LOT).bucket(0, 65_000, 65_000, 65_000, 65_000);

    assertThat(session.warns()).as("the flagship defect keeps its old latency").isEqualTo(1.0);
    assertThat(session.straddles()).isZero();
  }

  @Test
  void aSustainedSameSignDriftWarnsWithoutWaitingOutThePairWindow() {
    // two consecutive straddle-SHAPED buckets skewed in the SAME direction: the second can never be
    // the first's partner, so the first is released the moment the second is classified — a
    // persistent defect must not inherit the deferral latency.
    Replay session =
        Replay.start(NIFTY_LOT)
            .bucket(0, 476_840, 153_335, 153_335, 153_335) // +16,835
            .bucket(3, 158_080, 47_081, 47_082, 47_082); //   +16,835 again (same sign)

    assertThat(session.warns()).as("bucket 1 released at the usual latency").isEqualTo(1.0);
    assertThat(session.straddles()).isZero();

    session.sweepAt(10, 1); // and bucket 2's own window then closes
    assertThat(session.warns()).isEqualTo(2.0);
  }

  @Test
  void aPairWhoseMagnitudesDoNotMatchStillWarns() {
    // opposite signs on consecutive buckets and both exact lot multiples, but 259 lots against 100
    // lots: 10,335 of the skew does NOT cancel, far beyond the 650 the canary treats as noise.
    Replay session =
        Replay.start(NIFTY_LOT)
            .bucket(0, 476_840, 153_335, 153_335, 153_335) // +16,835 (259 lots)
            .bucket(3, 134_745, 47_081, 47_082, 47_082); //   -6,500  (100 lots)

    assertThat(session.warns()).as("an uncancelled residue is a defect, not a straddle").isEqualTo(1.0);
    assertThat(session.straddles()).isZero();

    session.sweepAt(10, 1); // the second half is unpaired in its own right
    assertThat(session.warns()).isEqualTo(2.0);
  }

  @Test
  void aPerfectPairThatIsNotALotMultipleStillWarnsBothHalves() {
    // consecutive, opposite, exactly cancelling — but 16,834 is not a multiple of the 65 lot, so it
    // cannot be trades landing one bucket late. Neither half is even deferred.
    Replay session =
        Replay.start(NIFTY_LOT)
            .bucket(0, 476_839, 153_335, 153_335, 153_335) // +16,834
            .bucket(3, 124_411, 47_081, 47_082, 47_082); //   -16,834

    assertThat(session.warns()).as("a non-lot-quantised pair is not a straddle").isEqualTo(2.0);
    assertThat(session.straddles()).isZero();
  }

  @Test
  void anUnresolvableLotSizeKeepsThePairRuleClosed() {
    // the instrument master could not answer, so we cannot prove the skews are lot-quantised and
    // the 07-29 pair is reported rather than suppressed. Fail CLOSED — a pager never goes quiet
    // because a lookup failed.
    Replay session =
        Replay.start(UNKNOWN_LOT)
            .bucket(0, 476_840, 153_335, 153_335, 153_335)
            .bucket(3, 124_410, 47_081, 47_082, 47_082);

    assertThat(session.warns()).as("unprovable pairs still WARN").isEqualTo(2.0);
    assertThat(session.straddles()).isZero();
  }

  @Test
  void armingTheScalingPctWouldSplitTheBenignPairAndManufactureAnUnpairedWarn() {
    // WHY volume-tolerance-pct MUST STAY 0 even now that pair awareness exists (G9 recommendation).
    // The arm is per-bucket: max(650, 5% x 460,005) = 23,000 absorbs the thick half (16,835), so it
    // is never held — while max(650, 5% x 141,245) = 7,062 leaves the thin half firing with no
    // partner to corroborate it. Arming pct converts a benign PAIR into exactly the unpaired
    // signature README §3.17 teaches operators to read as a defect.
    MovableClock clock = new MovableClock(OPEN.toInstant());
    LiveSeriesStore store = new LiveSeriesStore(null, clock);
    MeterRegistry registry = new SimpleMeterRegistry();
    Replay armed =
        new Replay(
            clock,
            store,
            registry,
            new PartialBucketCanary(store, clock, registry, 650L, 5.0, NIFTY_LOT, NO_STORE));
    armed
        .bucket(0, 476_840, 153_335, 153_335, 153_335)
        .bucket(3, 124_410, 47_081, 47_082, 47_082)
        .sweepAt(10, 1);

    assertThat(armed.warns()).as("pct=5 manufactures a false UNPAIRED warn").isEqualTo(1.0);
    assertThat(armed.straddles()).as("the pair rule never sees two halves").isZero();
  }

  @Test
  void aFrozenPartnerBucketIsNeverSuppressedByAnEarlierSmallHalf() {
    // CRITICAL 1 (cross-vendor review, 2026-08-01): the 25% shape cap used to be applied only when
    // CREATING the held half, so an incoming partner escaped it entirely. Here bucket N is a
    // legitimate-looking 6,500 skew on a 30,000 bucket (21.7%, held) and bucket N+1 is a genuine
    // frozen first-minute bar — 3,250 of a true 9,750 — whose shortfall happens to be exactly
    // -6,500. Consecutive, opposite, both x65 lot multiples, residue ZERO: the pair rule would have
    // suppressed a real frozen bucket with an INFO line. The residue bound limits NET unexplained
    // volume; gross per-bucket corruption is what matters, so BOTH halves are shape-capped now.
    Replay session =
        Replay.start(NIFTY_LOT)
            .bucket(0, 36_500, 10_000, 10_000, 10_000) //  sum 30,000 -> +6,500 (21.7%, held)
            .bucket(3, 3_250, 3_250, 3_250, 3_250); //     sum  9,750 -> -6,500 (66.7%, FROZEN)

    assertThat(session.warns()).as("a frozen bucket is never explained away by a pair").isEqualTo(2.0);
    assertThat(session.straddles()).as("this is not a straddle").isZero();
  }

  /** Appends one 3m bucket + its three 1m bars at {@code OPEN + minutesFromOpen}, without sweeping. */
  private static void appendBucket(
      LiveSeriesStore store, int minutesFromOpen, long threeMinute, long m0, long m1, long m2) {
    OffsetDateTime at = OPEN.plusMinutes(minutesFromOpen);
    store.append(ONE_MIN, bar(at, m0));
    store.append(ONE_MIN, bar(at.plusMinutes(1), m1));
    store.append(ONE_MIN, bar(at.plusMinutes(2), m2));
    store.append(THREE_MIN, bar(at, threeMinute));
  }

  @Test
  void aHeldHalfIsCarriedAcrossARestartAndStillReported() {
    // CRITICAL 2 (cross-vendor review, 2026-08-01). The hold is an OBSERVATION that has not been
    // reported. Re-deriving it after a restart is impossible - the 3m series is a SQL rollup of the
    // very same `candles` 1m rows the 1m series is re-warmed from (CandleQueryService:103-105), so
    // a re-read compares DB against DB and is benign by construction; the diverging side (live
    // tick-agg, never revised) died with the process. So the observation itself is carried.
    // Modelled properly: process 1 defers, process 2 is a FRESH canary (empty in-memory maps)
    // sharing only the durable store.
    PartialBucketCanary.HeldHalves durable = store();
    MovableClock clock = new MovableClock(OPEN.toInstant());
    LiveSeriesStore store = new LiveSeriesStore(null, clock);
    MeterRegistry beforeRestart = new SimpleMeterRegistry();

    appendBucket(store, 0, 476_840, 153_335, 153_335, 153_335); // +16,835, straddle-shaped
    clock.set(OPEN.toInstant().plus(Duration.ofMinutes(3)).plus(Duration.ofSeconds(30)));
    new PartialBucketCanary(store, clock, beforeRestart, 650L, 0.0, NIFTY_LOT, durable).sweep();
    assertThat(beforeRestart.counter(COUNTER).count()).as("deferred, not yet reported").isZero();

    // ---- restart. THREE further buckets complete while the process is down, all healthy, so the
    // held bucket is no longer the penultimate one: a fixed look-back of any depth would miss it.
    appendBucket(store, 3, 141_245, 47_081, 47_082, 47_082);
    appendBucket(store, 6, 90_000, 30_000, 30_000, 30_000);
    appendBucket(store, 9, 90_000, 30_000, 30_000, 30_000);
    clock.set(OPEN.toInstant().plus(Duration.ofMinutes(12)).plus(Duration.ofSeconds(30)));
    MeterRegistry afterRestart = new SimpleMeterRegistry();

    new PartialBucketCanary(store, clock, afterRestart, 650L, 0.0, NIFTY_LOT, durable).sweep();

    assertThat(afterRestart.counter(COUNTER).count())
        .as("the carried observation is reported however long the restart lasted")
        .isEqualTo(1.0);
    assertThat(afterRestart.counter(STRADDLES).count()).isZero();
  }

  @Test
  void aHalfIsNeverDeferredWhenItCannotBeStoredDurably() {
    // The invariant that makes the restart guarantee unconditional: no durable store (no Redis
    // bean, or Redis down) means the canary does not defer at all - it WARNs immediately, exactly
    // as before G9. Either the hold survives a restart or it was never a hold.
    Replay session =
        Replay.start(NIFTY_LOT, NO_STORE).bucket(0, 476_840, 153_335, 153_335, 153_335);

    assertThat(session.warns()).as("undurable ⇒ reported now, never silently held").isEqualTo(1.0);
    assertThat(session.straddles()).isZero();
  }

  /** A durable store that dies exactly at the delete — the crash window write-ahead ordering guards. */
  private static PartialBucketCanary.HeldHalves storeThatDiesOnClear() {
    Map<String, PartialBucketCanary.Held> backing = new LinkedHashMap<>();
    return new PartialBucketCanary.HeldHalves() {
      @Override
      public boolean save(SeriesKey key, PartialBucketCanary.Held half) {
        backing.put(key.canonical(), half);
        return true;
      }

      @Override
      public Optional<Map<String, PartialBucketCanary.Held>> carried() {
        return Optional.of(new LinkedHashMap<>(backing));
      }

      @Override
      public void clear(String seriesId) {
        throw new IllegalStateException("process died at the delete");
      }
    };
  }

  @Test
  void theObservationIsReportedBeforeTheDurableRecordIsDeleted() {
    // WRITE-AHEAD ORDERING (cross-vendor review round 4). Deleting the durable record first meant a
    // death between the delete and the counter/log lost a genuine mismatch outright — the exact
    // restart class this persistence was built to prevent. Reporting first can at worst DUPLICATE a
    // WARN after a crash, never erase one. Simulated by a store that dies at the delete: the
    // counter must already have moved by then.
    Replay session =
        Replay.start(NIFTY_LOT, storeThatDiesOnClear())
            .bucket(0, 476_840, 153_335, 153_335, 153_335) // +16,835, deferred
            .bucket(3, 141_245, 47_081, 47_082, 47_082); //   healthy — the half is unexplained

    assertThat(session.warns())
        .as("reported before the delete, so a crash at the delete cannot erase it")
        .isEqualTo(1.0);
  }

  @Test
  void theDeadlineReleaseAlsoReportsBeforeDeleting() {
    // the same ordering on the wall-clock release path, which had the identical defect.
    Replay session = Replay.start(NIFTY_LOT, storeThatDiesOnClear());
    session.bucket(0, 476_840, 153_335, 153_335, 153_335); // deferred, no partner ever arrives
    assertThat(session.warns()).isZero();

    session.sweepAt(7, 1); // 09:22:01 — the pair window closes and the half is released

    assertThat(session.warns()).as("released before the delete").isEqualTo(1.0);
  }

  @Test
  void aHoldOnASeriesThatNoLongerTradesIsStillReported() {
    // CRITICAL 2 (cross-vendor review round 4): recovery must NOT be indexed by the live series
    // set. A monthly roll means the contract carrying the hold is simply gone after the restart —
    // no sweep would ever reach it, so the unreported mismatch would sit in Redis for ever.
    // "Is this alarm still reachable" and "is this contract still trading" are unrelated questions.
    PartialBucketCanary.HeldHalves durable = store();
    MovableClock clock = new MovableClock(OPEN.toInstant());
    LiveSeriesStore store = new LiveSeriesStore(null, clock);

    // process 1 defers a half on the July contract
    SeriesKey julyThree = new SeriesKey("NFO", "NIFTY26JULFUT", "3m");
    SeriesKey julyOne = new SeriesKey("NFO", "NIFTY26JULFUT", "1m");
    store.append(julyOne, bar(OPEN, 153_335));
    store.append(julyOne, bar(OPEN.plusMinutes(1), 153_335));
    store.append(julyOne, bar(OPEN.plusMinutes(2), 153_335));
    store.append(julyThree, bar(OPEN, 476_840)); // +16,835, straddle-shaped
    clock.set(OPEN.toInstant().plus(Duration.ofMinutes(3)).plus(Duration.ofSeconds(30)));
    new PartialBucketCanary(
            store, clock, new SimpleMeterRegistry(), 650L, 0.0, NIFTY_LOT, durable)
        .sweep();

    // ---- restart AFTER the roll: only the AUGUST contract is warmed now, so nothing in the live
    // set will ever lead back to the July hold. (Distinct symbols matter — an earlier revision of
    // this test reused the July keys and passed with orphan recovery disabled.)
    SeriesKey augThree = new SeriesKey("NFO", "NIFTY26AUGFUT", "3m");
    SeriesKey augOne = new SeriesKey("NFO", "NIFTY26AUGFUT", "1m");
    LiveSeriesStore rolled = new LiveSeriesStore(null, clock);
    rolled.append(augOne, bar(OPEN, 30_000));
    rolled.append(augOne, bar(OPEN.plusMinutes(1), 30_000));
    rolled.append(augOne, bar(OPEN.plusMinutes(2), 30_000));
    rolled.append(augThree, bar(OPEN, 90_000)); // healthy August series
    clock.set(OPEN.toInstant().plus(Duration.ofMinutes(20)));
    MeterRegistry afterRoll = new SimpleMeterRegistry();

    new PartialBucketCanary(rolled, clock, afterRoll, 650L, 0.0, NIFTY_LOT, durable).sweep();

    assertThat(afterRoll.counter(COUNTER).count())
        .as("the orphaned hold is enumerated from the durable index, not from live series")
        .isEqualTo(1.0);
    assertThat(durable.carried().orElseThrow())
        .as("and cleared once reported, so it is not re-reported for ever")
        .isEmpty();
  }

  @Test
  void anUnreadableStoreIsTreatedAsUnknownAndRetriedRatherThanAsAbsence() {
    // A read FAILURE must never be collapsed into "nothing was carried" — one Redis blip would then
    // permanently swallow an unreported observation. While the carried state is unknown the canary
    // (a) does not consume the restore, so the next sweep retries, and (b) refuses to defer, so a
    // new hold cannot overwrite the older one it has not managed to read.
    MovableClock clock = new MovableClock(OPEN.toInstant());
    LiveSeriesStore store = new LiveSeriesStore(null, clock);
    MeterRegistry registry = new SimpleMeterRegistry();
    PartialBucketCanary canary =
        new PartialBucketCanary(store, clock, registry, 650L, 0.0, NIFTY_LOT, UNREADABLE_STORE);

    appendBucket(store, 0, 476_840, 153_335, 153_335, 153_335); // straddle-shaped, would defer
    clock.set(OPEN.toInstant().plus(Duration.ofMinutes(3)).plus(Duration.ofSeconds(30)));
    canary.sweep();

    assertThat(registry.counter(COUNTER).count())
        .as("cannot prove the store is empty ⇒ report rather than defer")
        .isEqualTo(1.0);
    assertThat(registry.counter(STRADDLES).count()).isZero();
  }

  @Test
  void aStraddleSpanningARestartWarnsBothHalvesBecauseLotSizesStartUnresolved() {
    // NOT a suppression guarantee - the opposite, pinned so the enumeration cannot drift back to
    // claiming one. A fresh process starts with an EMPTY lot cache, and the fail-closed rule
    // (unknown lot ⇒ cannot prove lot-quantisation ⇒ no pairing) takes precedence, so the first
    // pair after a restart WARNs BOTH halves. Two independently safe behaviours composing into
    // extra noise, never into silence. An earlier revision of this suite asserted suppression here
    // and passed only because the fixture pre-warmed a lot size production would not have.
    PartialBucketCanary.HeldHalves durable = store();
    MovableClock clock = new MovableClock(OPEN.toInstant());
    LiveSeriesStore store = new LiveSeriesStore(null, clock);

    appendBucket(store, 0, 476_840, 153_335, 153_335, 153_335); // +16,835
    clock.set(OPEN.toInstant().plus(Duration.ofMinutes(3)).plus(Duration.ofSeconds(30)));
    // process 1 had a warm lot cache, so it deferred
    new PartialBucketCanary(
            store, clock, new SimpleMeterRegistry(), 650L, 0.0, NIFTY_LOT, durable)
        .sweep();

    // ---- restart. The true partner completes; the new process's lot cache is COLD.
    appendBucket(store, 3, 124_410, 47_081, 47_082, 47_082); // -16,835, the true partner
    clock.set(OPEN.toInstant().plus(Duration.ofMinutes(6)).plus(Duration.ofSeconds(30)));
    MeterRegistry afterRestart = new SimpleMeterRegistry();

    new PartialBucketCanary(store, clock, afterRestart, 650L, 0.0, UNKNOWN_LOT, durable).sweep();

    assertThat(afterRestart.counter(STRADDLES).count())
        .as("a cold process cannot prove the pair, so it must not suppress it")
        .isZero();
    assertThat(afterRestart.counter(COUNTER).count())
        .as("both halves are reported: noise, not silence")
        .isEqualTo(2.0);
  }

  @Test
  void theFirstNonBenignBucketOfAContractCannotPairWhileTheLotSizeIsUnresolved() {
    // The live lot-size source is a NON-BLOCKING cache (the sweep runs on the single-threaded
    // monitor pool, whose contract forbids blocking external calls), so a cache miss returns null
    // and fires an off-pool prefetch. Fail-closed consequence, pinned here: the first pair a
    // process sees WARNs both halves; once the prefetch has landed, later pairs suppress normally.
    AtomicBoolean prefetched = new AtomicBoolean(false);
    LotSizes warmingUp = (exchange, tradingsymbol) -> prefetched.get() ? 65L : null;

    Replay session =
        Replay.start(warmingUp)
            .bucket(0, 476_840, 153_335, 153_335, 153_335) // lot unknown -> cannot pair -> WARN
            .bucket(3, 124_410, 47_081, 47_082, 47_082); //   lot unknown -> cannot pair -> WARN
    assertThat(session.warns()).as("an unprovable pair fails closed").isEqualTo(2.0);
    assertThat(session.straddles()).isZero();

    prefetched.set(true); // the off-pool lookup has landed
    session
        .bucket(6, 64_025, 20_215, 20_215, 20_215) //  sum 60,645 -> +3,380, held
        .bucket(9, 53_040, 18_806, 18_807, 18_807); // sum 56,420 -> -3,380, pairs

    assertThat(session.straddles()).as("once resolved, pairs suppress normally").isEqualTo(1.0);
    assertThat(session.warns()).as("and no further WARN").isEqualTo(2.0);
  }
}

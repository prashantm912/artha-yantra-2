package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SignalEngine.Outcome;
import in.arthayantra.strategysignal.signals.SignalEngine.StrategyEvalKey;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * F5 unit U2: the PER-STRATEGY evaluation dimension, driven through the REAL {@link SignalEngine}
 * increment site.
 *
 * <p><b>What was missing.</b> {@code outcomeCounters} is an {@code EnumMap<Outcome, Counter>} with no
 * per-strategy dimension, so the engine could report "the fleet evaluated 4 bars" but never "A
 * evaluated 3 and B evaluated 1". Every per-strategy support-rate and pass-rate therefore ran against
 * a fleet denominator or a hand-reconstructed one. {@link
 * #twoStrategiesEvaluatingDifferentBarCountsGetDistinctTotals()} is the red-proof: it asserts BOTH
 * sides — the per-slug split that is new, and the merged fleet total that is all the old counters
 * could ever say.
 *
 * <p>The database half (day-keying, double-flush idempotency, restart-mid-day) lives in {@link
 * StrategyEvalDenominatorIntegrationTest}; those are properties of the write protocol, not of the
 * counters.
 */
class SignalEngineStrategyEvalCountTest {

  private static final LocalDate SESSION = LocalDate.of(2026, 7, 31);

  /**
   * A real engine wired with mocks — the same construction {@code SignalEngineLatencyTest} uses, and
   * closed the same way (the engine owns daemon executors; the registry owns meters).
   *
   * <p>Package-visible rather than private because {@link StrategyEvalDenominatorIntegrationTest}
   * reuses it to drive the REAL engine against the REAL table for the late-bar case; duplicating a
   * 20-line constructor in two files invites them to drift apart.
   */
  record Fixture(SignalEngine engine, PrometheusMeterRegistry meters) implements AutoCloseable {

    static Fixture create() {
      PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
      FuturesUniverseResolver resolver = mock(FuturesUniverseResolver.class);
      when(resolver.resolve(anyString(), anyString(), anyString(), anyInt()))
          .thenReturn(Optional.of(List.of()));
      SignalEngine engine =
          new SignalEngine(
              mock(StrategyRepository.class),
              mock(SignalRepository.class),
              mock(SignalPublisher.class),
              mock(ApplicationEventPublisher.class),
              mock(LiveSeriesStore.class),
              resolver,
              mock(RedisConnectionFactory.class),
              new ObjectMapper(),
              Clock.fixed(Instant.parse("2026-07-31T06:00:00Z"), ZoneOffset.UTC),
              meters,
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              mock(RejectionWriter.class),
              mock(RiskSuppressionWriter.class),
              mock(CompositeRejectionWriter.class),
              Optional.empty(),
              mock(PlatformTransactionManager.class),
              60,
              true);
      return new Fixture(engine, meters);
    }

    /** The fleet-level counter — everything the pre-change engine could report. */
    long fleetTotal(Outcome outcome) {
      return (long)
          meters
              .find("ay_signal_eval_outcome_total")
              .tag("outcome", outcome.tag())
              .counter()
              .count();
    }

    Map<StrategyEvalKey, Long> perStrategy() {
      return engine.strategyEvalSnapshot().counts();
    }

    @Override
    public void close() {
      engine.stop();
      meters.close();
    }
  }

  /** Only {@code slug()} is read by the counter path; the rest of the record is inert here. */
  static SignalEngine.Loaded strategy(String slug) {
    return new SignalEngine.Loaded(
        UUID.randomUUID(), UUID.randomUUID(), slug, slug, "1", "checksum",
        null, List.of(), Set.of(), null, null);
  }

  /** A bar at {@code istTime} on {@code session}, carrying the IST offset like every live bar. */
  static EngineCandle bar(LocalDate session, String istTime) {
    return new EngineCandle(
        OffsetDateTime.of(session, LocalTime.parse(istTime), Ist.OFFSET),
        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0L);
  }

  @Test
  void twoStrategiesEvaluatingDifferentBarCountsGetDistinctTotals() {
    try (Fixture fixture = Fixture.create()) {
      SignalEngine.Loaded a = strategy("scalp-denominator-a");
      SignalEngine.Loaded b = strategy("scalp-denominator-b");

      fixture.engine().countEvaluation(a, bar(SESSION, "09:15"), Outcome.CHART_GATE_FAILED);
      fixture.engine().countEvaluation(a, bar(SESSION, "09:18"), Outcome.CHART_GATE_FAILED);
      fixture.engine().countEvaluation(a, bar(SESSION, "09:21"), Outcome.CHART_GATE_FAILED);
      fixture.engine().countEvaluation(b, bar(SESSION, "09:15"), Outcome.CHART_GATE_FAILED);

      // THE NEW CAPABILITY: A and B are separable, so each has its own denominator.
      assertThat(fixture.perStrategy())
          .containsEntry(
              new StrategyEvalKey(SESSION, "scalp-denominator-a", Outcome.CHART_GATE_FAILED), 3L)
          .containsEntry(
              new StrategyEvalKey(SESSION, "scalp-denominator-b", Outcome.CHART_GATE_FAILED), 1L);

      // THE RED-PROOF: the counter that shipped before this change reports ONE merged number. A
      // per-strategy rate computed from it silently uses the wrong denominator for BOTH strategies.
      assertThat(fixture.fleetTotal(Outcome.CHART_GATE_FAILED))
          .as("the pre-existing fleet counter can only say 4 — never 3-and-1")
          .isEqualTo(4L);
    }
  }

  @Test
  void theFleetCounterIsUnchangedAndTheTwoResolutionsAlwaysAgree() {
    // Additive, and provably so: both increments happen in ONE method, so Σ(per-slug) == fleet by
    // construction rather than by two call sites agreeing.
    try (Fixture fixture = Fixture.create()) {
      SignalEngine.Loaded a = strategy("scalp-agree-a");
      SignalEngine.Loaded b = strategy("scalp-agree-b");

      fixture.engine().countEvaluation(a, bar(SESSION, "09:15"), Outcome.FIRED);
      fixture.engine().countEvaluation(a, bar(SESSION, "09:18"), Outcome.COMPOSITE_BELOW_THRESHOLD);
      fixture.engine().countEvaluation(b, bar(SESSION, "09:15"), Outcome.COMPOSITE_BELOW_THRESHOLD);
      fixture.engine().countEvaluation(b, bar(SESSION, "09:18"), Outcome.COMPOSITE_BELOW_THRESHOLD);

      long perSlugBelowThreshold =
          fixture.perStrategy().entrySet().stream()
              .filter(entry -> entry.getKey().outcome() == Outcome.COMPOSITE_BELOW_THRESHOLD)
              .mapToLong(Map.Entry::getValue)
              .sum();

      assertThat(perSlugBelowThreshold)
          .isEqualTo(fixture.fleetTotal(Outcome.COMPOSITE_BELOW_THRESHOLD))
          .isEqualTo(3L);
      assertThat(fixture.fleetTotal(Outcome.FIRED)).isEqualTo(1L);
      // Only combinations that actually happened exist — an absent key is a genuine zero, which is
      // what a DENOMINATOR needs. (Process liveness stays V045's question; it still writes zeros.)
      assertThat(fixture.perStrategy()).hasSize(3);
    }
  }

  @Test
  void countsAreKeyedByTheBarsIstSessionDateNotByTheWallClock() {
    // The date travels with the count, so a flush that happens later — the next morning, after a
    // weekend — still attributes it to the session it came from. This is exactly what V045 cannot
    // do: a Micrometer counter carries a total and no timing, which is why a cross-date recovery
    // there is unattributable. Here there is nothing to attribute after the fact.
    try (Fixture fixture = Fixture.create()) {
      SignalEngine.Loaded a = strategy("scalp-session-date");

      fixture.engine().countEvaluation(a, bar(SESSION, "15:57"), Outcome.FIRED);
      // 04:00 UTC is the NEXT UTC day here but 09:30 IST the following morning. Reading
      // toLocalDate() off the raw offset instead of normalizing to IST is the standing off-by-one.
      fixture
          .engine()
          .countEvaluation(
              a,
              new EngineCandle(
                  OffsetDateTime.parse("2026-08-01T04:00:00Z"),
                  BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0L),
              Outcome.FIRED);

      assertThat(fixture.perStrategy())
          .containsEntry(new StrategyEvalKey(SESSION, "scalp-session-date", Outcome.FIRED), 1L)
          .containsEntry(
              new StrategyEvalKey(LocalDate.of(2026, 8, 1), "scalp-session-date", Outcome.FIRED),
              1L);
    }
  }

  @Test
  void aPriorSessionBarEvaluatedAfterTheRolloverStillLandsOnItsOwnKey() {
    // THE FALSIFYING CASE cross-vendor review named (Critical 1). The single eval FIFO can still
    // hold prior-session bars after a JDBC or eval-thread stall. Under the REJECTED design — the
    // rollup thread pruning against the FLUSH CLOCK — such a bar arrives after the prune, is
    // accepted as strictly increasing (first bar of a freshly recreated series key), recreates the
    // key FROM ZERO, and the next `SET eval_count = EXCLUDED.eval_count` overwrites the larger
    // durable total with the smaller fresh one: the session's counts lost AND the row regressed.
    // (A GREATEST clamp would not have saved it — it stops the regression while still dropping the
    // late evaluations, which is why it was never the fix.)
    try (Fixture fixture = Fixture.create()) {
      SignalEngine.Loaded a = strategy("scalp-straggler");
      StrategyEvalKey yesterday = new StrategyEvalKey(SESSION, "scalp-straggler", Outcome.FIRED);

      fixture.engine().countEvaluation(a, bar(SESSION, "15:57"), Outcome.FIRED);
      fixture.engine().countEvaluation(a, bar(SESSION, "15:58"), Outcome.FIRED);
      // The eval thread advances to the next session — this is what now triggers the prune.
      fixture.engine().countEvaluation(a, bar(SESSION.plusDays(1), "09:15"), Outcome.FIRED);
      assertThat(fixture.perStrategy()).containsEntry(yesterday, 2L);

      // ...and NOW the stalled 15:59 bar finally drains.
      fixture.engine().countEvaluation(a, bar(SESSION, "15:59"), Outcome.FIRED);

      assertThat(fixture.perStrategy())
          .as("the straggler is counted (3, not a fresh key at 1) — nothing regresses, nothing lost")
          .containsEntry(yesterday, 3L)
          .containsEntry(new StrategyEvalKey(SESSION.plusDays(1), "scalp-straggler", Outcome.FIRED), 1L);
      assertThat(fixture.fleetTotal(Outcome.FIRED)).isEqualTo(4L);
    }
  }

  @Test
  void theMapIsBoundedToTheTwoMostRecentlyCountedSessions() {
    // The bound the prune exists for: without it the map grows by up to 441 entries per day of
    // uptime and every past day's rows are rewritten on every 3m tick. Two sessions rather than one
    // is what buys the straggler window above.
    try (Fixture fixture = Fixture.create()) {
      SignalEngine.Loaded a = strategy("scalp-bounded");

      fixture.engine().countEvaluation(a, bar(SESSION, "09:15"), Outcome.FIRED);
      fixture.engine().countEvaluation(a, bar(SESSION.plusDays(1), "09:15"), Outcome.FIRED);
      assertThat(fixture.perStrategy()).as("two sessions are retained").hasSize(2);

      fixture.engine().countEvaluation(a, bar(SESSION.plusDays(2), "09:15"), Outcome.FIRED);

      assertThat(fixture.perStrategy())
          .containsOnlyKeys(
              new StrategyEvalKey(SESSION.plusDays(1), "scalp-bounded", Outcome.FIRED),
              new StrategyEvalKey(SESSION.plusDays(2), "scalp-bounded", Outcome.FIRED));
      assertThat(fixture.engine().retainedSessions())
          .isEqualTo(new SignalEngine.RetainedSessions(SESSION.plusDays(2), SESSION.plusDays(1)));
      // The fleet counters are NOT pruned — V045's record is independent of this bound.
      assertThat(fixture.fleetTotal(Outcome.FIRED)).isEqualTo(3L);
    }
  }

  @Test
  void aWeekendGapRetainsFridayThroughMonday() {
    // The retention is "the two most recently COUNTED sessions", not calendar arithmetic — so a
    // Friday→Monday gap keeps Friday alive all Monday, exactly like any consecutive pair.
    try (Fixture fixture = Fixture.create()) {
      SignalEngine.Loaded a = strategy("scalp-weekend");
      LocalDate friday = LocalDate.of(2026, 7, 24);
      LocalDate monday = LocalDate.of(2026, 7, 27);

      fixture.engine().countEvaluation(a, bar(friday, "15:57"), Outcome.FIRED);
      fixture.engine().countEvaluation(a, bar(monday, "09:15"), Outcome.FIRED);
      fixture.engine().countEvaluation(a, bar(friday, "15:59"), Outcome.FIRED); // weekend straggler

      assertThat(fixture.perStrategy())
          .containsEntry(new StrategyEvalKey(friday, "scalp-weekend", Outcome.FIRED), 2L)
          .containsEntry(new StrategyEvalKey(monday, "scalp-weekend", Outcome.FIRED), 1L);
    }
  }
}

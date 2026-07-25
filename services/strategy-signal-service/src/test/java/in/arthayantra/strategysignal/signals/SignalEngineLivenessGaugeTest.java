package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The engine-liveness read surface (chip task_0bed1621).
 *
 * <p>`lastBarReceivedAtMs` and `lastBarEvaluatedAtMs` are the ONLY unconfounded engine-liveness
 * oracles, and until this landed nothing outside the process could read them — so on a fully quiet
 * session an operator could not prove the engine was alive at all. Every other readable signal
 * admits a false PASS (rejections are direction-dependent, `strategy.signals` mixes the swing BATCH
 * engine, `subscriber_health_events` is write-only fail-soft, and `signal_eval_outcomes` freshness
 * only proves the ROLLUP thread is alive).
 *
 * <p>Both gauges are asserted PRE-REGISTERED at boot for the #895 reason: a lazily-created meter
 * would leave a MISSING series indistinguishable from a dead engine — the exact ambiguity this
 * exists to remove, one level up.
 */
class SignalEngineLivenessGaugeTest {

  /** A clock the test can advance; `Clock.fixed` cannot express "time passed with no bar". */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advanceSeconds(long seconds) {
      now = now.plusSeconds(seconds);
    }

    void rewindSeconds(long seconds) {
      now = now.minusSeconds(seconds);
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

  @Test
  void bothHeartbeatsArePreRegisteredAtBootAndTrackStaleness() {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_000_000));
    PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    SignalEngine engine = engine(clock, meters);
    try {
      // Registered at boot, before any bar has ever arrived — never lazily created.
      assertThat(meters.find("ay_signal_bar_received_age_seconds").gauge()).isNotNull();
      assertThat(meters.find("ay_signal_bar_evaluated_age_seconds").gauge()).isNotNull();

      // NO bar has ever arrived, so the age is NOT 0 — it is the -1 sentinel. The stamps are
      // seeded at construction for the canary's boot grace, so a plain age would read ~0 here and
      // be indistinguishable from a bar that just landed. That ambiguity is the defect this
      // sentinel removes; an earlier draft published 0 and would have read as healthy forever on
      // an engine that never received anything.
      assertThat(received(meters)).isEqualTo(-1.0);
      assertThat(evaluated(meters)).isEqualTo(-1.0);

      // Still -1 after time passes: "never started" does not age into "stale".
      clock.advanceSeconds(185);
      assertThat(received(meters)).isEqualTo(-1.0);
    } finally {
      engine.stop();
      meters.close();
    }
  }

  /**
   * A real bar makes the age valid and it must then GROW — the gauge is useless if it does not
   * track staleness.
   */
  @Test
  void onceABarArrivesTheAgeBecomesValidAndTracksStaleness() {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_000_000));
    PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    SignalEngine engine = engine(clock, meters);
    try {
      engine.markBarReceivedForTest(clock.millis());
      assertThat(received(meters)).isZero();

      clock.advanceSeconds(185);
      assertThat(received(meters)).isEqualTo(185.0);
      // The evaluated side is independent: it has still never been stamped.
      assertThat(evaluated(meters)).isEqualTo(-1.0);
    } finally {
      engine.stop();
      meters.close();
    }
  }

  /**
   * A backwards clock step must NOT be clamped to 0. Clamping hid a genuine clock fault behind a
   * value that reads as freshly alive — and an 87-minute host drift in July 2026 is exactly the
   * fault an operator needs to see rather than have smoothed away.
   */
  @Test
  void aBackwardsClockStepPublishesAnInvalidAgeRatherThanLookingFresh() {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_000_000));
    PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    SignalEngine engine = engine(clock, meters);
    try {
      engine.markBarReceivedForTest(clock.millis());
      clock.rewindSeconds(600);

      assertThat(received(meters)).isLessThan(-1.0); // distinct from the -1 never-started sentinel
    } finally {
      engine.stop();
      meters.close();
    }
  }

  private static double received(PrometheusMeterRegistry meters) {
    return meters.find("ay_signal_bar_received_age_seconds").gauge().value();
  }

  private static double evaluated(PrometheusMeterRegistry meters) {
    return meters.find("ay_signal_bar_evaluated_age_seconds").gauge().value();
  }

  private static SignalEngine engine(Clock clock, PrometheusMeterRegistry meters) {
    FuturesUniverseResolver resolver = mock(FuturesUniverseResolver.class);
    when(resolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(Optional.of(List.of()));
    return new SignalEngine(
        mock(StrategyRepository.class),
        mock(SignalRepository.class),
        mock(SignalPublisher.class),
        mock(ApplicationEventPublisher.class),
        mock(LiveSeriesStore.class),
        resolver,
        mock(RedisConnectionFactory.class),
        new ObjectMapper(),
        clock,
        meters,
        Optional.empty(),
        Optional.empty(),
        mock(RejectionWriter.class),
        mock(RiskSuppressionWriter.class),
        mock(CompositeRejectionWriter.class),
        Optional.empty(),
        mock(PlatformTransactionManager.class),
        60,
        true);
  }
}

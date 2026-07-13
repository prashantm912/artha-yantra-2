package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class SignalEngineLatencyTest {

  @Test
  void leavesLatencyNullWhenNoBarReceiptTimestampExists() {
    assertThat(SignalEngine.emitLatencyMs(2_000L, 0L)).isNull();
  }

  @Test
  void measuresLatencyFromTheLastBarReceiptTimestamp() {
    assertThat(SignalEngine.emitLatencyMs(2_000L, 1_250L)).isEqualTo(750L);
  }

  @Test
  void leavesPersistedLatencyNullUntilARealBarIsBeingEvaluated() {
    try (Fixture fixture = fixture()) {
      fixture.engine().stampEmissionLatency(41L);

      verify(fixture.signals())
          .stampEmittedAt(eq(41L), eq(fixture.now()), isNull());
      assertThat(fixture.meters().find("ay_signal_bar_to_emit_seconds").timer().count())
          .isZero();
    }
  }

  @Test
  void usesTheReceiptTimestampScopedToTheBarBeingEvaluated() {
    try (Fixture fixture = fixture()) {
      fixture.engine().withBarReceiptTimestamp(
          1_250L, () -> fixture.engine().stampEmissionLatency(42L));

      verify(fixture.signals())
          .stampEmittedAt(eq(42L), eq(fixture.now()), eq(750L));
      assertThat(fixture.meters().find("ay_signal_bar_to_emit_seconds").timer().count())
          .isEqualTo(1L);
      assertThat(fixture.meters().scrape())
          .contains(
              "ay_signal_bar_to_emit_seconds{quantile=\"0.5\"}",
              "ay_signal_bar_to_emit_seconds{quantile=\"0.95\"}");
    }
  }

  @Test
  void telemetryPersistenceFailureCannotInterruptTheLiveSignalPath() {
    try (Fixture fixture = fixture()) {
      doThrow(new DataAccessResourceFailureException("latency side-channel unavailable"))
          .when(fixture.signals())
          .stampEmittedAt(anyLong(), any(OffsetDateTime.class), nullable(Long.class));

      assertThatCode(() -> fixture.engine().stampEmissionLatency(43L))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void telemetryMeterFailureCannotInterruptTheLiveSignalPath() {
    try (Fixture fixture = fixture()) {
      Timer throwingTimer = mock(Timer.class);
      doThrow(new IllegalStateException("meter unavailable"))
          .when(throwingTimer)
          .record(anyLong(), eq(TimeUnit.MILLISECONDS));
      ReflectionTestUtils.setField(fixture.engine(), "barToEmitTimer", throwingTimer);

      assertThatCode(
              () ->
                  fixture.engine().withBarReceiptTimestamp(
                      1_250L, () -> fixture.engine().stampEmissionLatency(44L)))
          .doesNotThrowAnyException();
    }
  }

  private static Fixture fixture() {
    SignalRepository signals = mock(SignalRepository.class);
    PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    Clock clock = Clock.fixed(Instant.ofEpochMilli(2_000L), ZoneOffset.UTC);
    SignalEngine engine =
        new SignalEngine(
            mock(StrategyRepository.class),
            signals,
            mock(SignalPublisher.class),
            mock(ApplicationEventPublisher.class),
            mock(LiveSeriesStore.class),
            mock(FuturesUniverseResolver.class),
            mock(RedisConnectionFactory.class),
            new ObjectMapper(),
            clock,
            meters,
            Optional.empty(),
            Optional.empty(),
            mock(SignalRejectionRepository.class),
            mock(ShadowBookService.class),
            mock(PlatformTransactionManager.class),
            60);
    return new Fixture(engine, signals, meters, OffsetDateTime.now(clock));
  }

  private record Fixture(
      SignalEngine engine,
      SignalRepository signals,
      PrometheusMeterRegistry meters,
      OffsetDateTime now)
      implements AutoCloseable {
    @Override
    public void close() {
      engine.stop();
      meters.close();
    }
  }
}

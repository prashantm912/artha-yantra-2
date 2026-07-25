package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T15 (bug queue B7): the reload ledger persists the boot line WITHOUT ever touching the caller —
 * reloads run on the signal-eval executor and the kite recovery chain, so the insert is enqueued
 * (bounded async) and a write failure is swallowed on the drain thread.
 */
class EngineReloadLedgerTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T05:30:00Z"), ZoneOffset.UTC);

  @Test
  void recordEnqueuesTheInsertWithTheReloadTimestamp() throws Exception {
    CountDownLatch inserted = new CountDownLatch(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              inserted.countDown();
              return 1;
            });
    EngineReloadLedger ledger = new EngineReloadLedger(jdbc, clock, new SimpleMeterRegistry());

    ledger.record(38, 0, 0, true);

    assertThat(inserted.await(5, TimeUnit.SECONDS)).as("drain thread ran the insert").isTrue();
    verify(jdbc, timeout(5000))
        .update(
            contains("INSERT INTO engine_reloads"),
            eq(java.sql.Timestamp.from(clock.instant())), eq(38), eq(0), eq(0), eq(true));
    ledger.shutdown();
  }

  @Test
  void aFailedInsertIsSwallowedOnTheDrainThreadNeverThrown() {
    when(jdbc.update(anyString(), any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("db down"));
    EngineReloadLedger ledger = new EngineReloadLedger(jdbc, clock, new SimpleMeterRegistry());

    assertThatCode(
            () -> {
              ledger.record(0, 38, 0, true);
              ledger.shutdown(); // drains the failing task; must not throw either
            })
        .doesNotThrowAnyException();
  }
}

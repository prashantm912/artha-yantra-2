package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import in.arthayantra.strategysignal.scalper.SentimentLevelShadow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * V056: the exit-oracle shadow writer rides the PROTECTIVE EXIT path, so it must never let a DB
 * stall or a persistence failure reach the sole {@code signal-eval} thread. A position that cannot
 * be evaluated cannot be exited — this is the one place where blocking on I/O costs money rather
 * than latency. Saturation DROPS + counts; a failing insert is swallowed; {@code record()} always
 * returns promptly and never throws.
 *
 * <p>Ported from {@link RiskSuppressionWriterTest} — same {@link BoundedAsyncWriter} seam, same
 * guarantees.
 */
class ExitOracleShadowWriterTest {

  private static final OffsetDateTime BAR = OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30));
  private static final SentimentLevelShadow SHADOW =
      new SentimentLevelShadow(new BigDecimal("0.00"), new BigDecimal("30"), true, true);

  private static void record(ExitOracleShadowWriter writer) {
    writer.record(42L, "scalp-trending-oi-nifty", BAR, "PE", "CE", "CE", true, SHADOW);
  }

  @Test
  void aStalledInsertNeverBlocksTheCallerAndSaturationDropsAndCounts() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    CountDownLatch block = new CountDownLatch(1);
    // The single writer thread parks on the first insert; the bounded queue then fills and the rest
    // are dropped — the caller (the eval thread, mid-exit-evaluation) must still return in O(1).
    doAnswer(
            inv -> {
              block.await();
              return null;
            })
        .when(repo)
        .insert(
            anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      int n = ExitOracleShadowWriter.QUEUE_CAPACITY * 8;
      long start = System.nanoTime();
      for (int i = 0; i < n; i++) {
        record(writer);
      }
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertThat(elapsedMs)
          .as("the eval thread is never blocked by the exit-oracle write path (%d records)", n)
          .isLessThan(2_000L);
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> meters.counter("ay_exit_oracle_shadow_dropped_total").count() > 0);
    } finally {
      block.countDown();
      writer.drainAndShutdown(100L);
    }
  }

  /**
   * A failing insert is the writer thread's problem, never the caller's. The measurement losing a
   * row is the CORRECT failure here — the alternative is an exception propagating into a protective
   * exit evaluation.
   */
  @Test
  void aFailingInsertIsSwallowedAndNeverReachesTheCaller() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    doThrow(new IllegalStateException("db down"))
        .when(repo)
        .insert(
            anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      assertThatCode(() -> record(writer)).doesNotThrowAnyException();
      verify(repo, timeout(5_000))
          .insert(
              anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
              any());
    } finally {
      writer.drainAndShutdown(500L);
    }
  }

  /** The happy path: every field of the shadow reaches the repository unchanged. */
  @Test
  void theShadowOperandsAndVerdictsArePersistedAsGiven() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      record(writer);
      verify(repo, timeout(5_000))
          .insert(
              42L, "scalp-trending-oi-nifty", BAR, "PE", "CE", "CE", true,
              new BigDecimal("0.00"), new BigDecimal("30"), true, true);
    } finally {
      writer.drainAndShutdown(500L);
    }
  }

  /**
   * A null verdict (market-data published no {@code sentimentLevelPct}) must persist as SQL NULL,
   * not be coerced to false. "Could not evaluate" and "the level says no" are different facts and
   * the analysis depends on telling them apart.
   */
  @Test
  void aMissingLevelPersistsNullVerdictsRatherThanFalse() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      writer.record(
          7L, "slug", BAR, "CE", "CE", null, false,
          new SentimentLevelShadow(new BigDecimal("1.5"), null, null, null));
      verify(repo, timeout(5_000))
          .insert(7L, "slug", BAR, "CE", "CE", null, false, new BigDecimal("1.5"), null, null, null);
    } finally {
      writer.drainAndShutdown(500L);
    }
  }
}

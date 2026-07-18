package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * PF-03: the risk-suppression writer must never let a DB stall or a persistence failure reach the
 * sole {@code signal-eval} thread (the #866 starvation class). Saturation DROPS + counts; a failing
 * insert is swallowed — {@code record()} always returns promptly and never throws.
 */
class RiskSuppressionWriterTest {

  private static final OffsetDateTime BAR = OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30));

  private static void record(RiskSuppressionWriter writer) {
    writer.record(
        UUID.randomUUID(), "slug", "other", "kill_switch", "NSE", "SIGTEST", "1m", "BUY", null,
        null, BAR);
  }

  @Test
  void aStalledInsertNeverBlocksTheCallerAndSaturationDropsAndCounts() throws InterruptedException {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    RiskSuppressionRepository repo = mock(RiskSuppressionRepository.class);
    CountDownLatch block = new CountDownLatch(1);
    // The single writer thread parks on the first insert; the bounded queue then fills and the rest
    // are dropped — the caller (the eval thread) must still return in O(1) throughout.
    doAnswer(
            inv -> {
              block.await();
              return 1L;
            })
        .when(repo)
        .insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    RiskSuppressionWriter writer = new RiskSuppressionWriter(repo, meters);
    try {
      int n = RiskSuppressionWriter.QUEUE_CAPACITY * 8;
      long start = System.nanoTime();
      for (int i = 0; i < n; i++) {
        record(writer);
      }
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertThat(elapsedMs)
          .as("the eval thread is never blocked by the write path (submitted %d records)", n)
          .isLessThan(2_000L);
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> meters.counter("ay_risk_suppression_dropped_total").count() > 0);
      assertThat(meters.counter("ay_risk_suppression_dropped_total").count())
          .as("a saturated queue drops rather than back-pressuring the eval loop")
          .isGreaterThan(0.0);
    } finally {
      block.countDown();
      writer.shutdown();
    }
  }

  @Test
  void aFailingInsertIsSwallowedAndNeverThrowsIntoTheCaller() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    RiskSuppressionRepository repo = mock(RiskSuppressionRepository.class);
    doThrow(new RuntimeException("boom"))
        .when(repo)
        .insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    RiskSuppressionWriter writer = new RiskSuppressionWriter(repo, meters);
    try {
      assertThatCode(() -> record(writer)).doesNotThrowAnyException();
      // The background thread DID attempt the insert (fail-soft proven) and swallowed the exception.
      verify(repo, timeout(2_000))
          .insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
      assertThat(meters.counter("ay_risk_suppression_dropped_total").count())
          .as("a failing insert is swallowed, not a drop")
          .isZero();
    } finally {
      writer.shutdown();
    }
  }
}

package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The composite-rejection writer must never let a DB stall or a persistence failure reach the sole
 * {@code signal-eval} thread (the #866 starvation class), and must never lose an accepted record
 * SILENTLY: saturation DROPS + counts, a failing insert is swallowed, and a shutdown that cannot
 * drain counts BOTH the abandoned queue and a stuck in-flight insert.
 *
 * <p>Ported from {@link RiskSuppressionWriterTest}. The in-flight case below is the one a
 * copied-shape writer gets wrong: {@code shutdownNow()} returns only QUEUED tasks, so the RUNNING
 * insert is invisible to it — with one record in flight and an empty queue, a naive implementation
 * reports zero drops and logs nothing while a row is lost. That is strictly worse than a visible
 * drop, and it is the defect this suite exists to pin.
 */
class CompositeRejectionWriterTest {

  private static final OffsetDateTime BAR = OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30));

  private static void record(CompositeRejectionWriter writer) {
    writer.record(
        UUID.randomUUID(), "slug", "NFO", "NIFTY26JULFUT", "3m",
        new BigDecimal("0.125"), new BigDecimal("0.2"), new BigDecimal("-0.075"), "{}", BAR);
  }

  private static void stubInsert(CompositeRejectionRepository repo, org.mockito.stubbing.Answer<?> a) {
    doAnswer(a)
        .when(repo)
        .insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aFailingInsertIsSwallowedAndNeverReachesTheEvalThread() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CompositeRejectionRepository repo = mock(CompositeRejectionRepository.class);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
        .when(repo)
        .insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    CompositeRejectionWriter writer = new CompositeRejectionWriter(repo, meters);

    assertThatCode(() -> record(writer)).doesNotThrowAnyException();
    writer.shutdown();
  }

  @Test
  void saturationDropsAndCountsWithoutBlockingTheCaller() throws InterruptedException {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CompositeRejectionRepository repo = mock(CompositeRejectionRepository.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch block = new CountDownLatch(1);
    stubInsert(
        repo,
        inv -> {
          started.countDown();
          try {
            block.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return 1L;
        });
    CompositeRejectionWriter writer = new CompositeRejectionWriter(repo, meters);
    try {
      record(writer);
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      // 1 stuck in flight + QUEUE_CAPACITY queued fills it; everything past that must DROP, not block
      for (int i = 0; i < CompositeRejectionWriter.QUEUE_CAPACITY + 20; i++) {
        record(writer);
      }
      assertThat(meters.counter("ay_composite_rejection_dropped_total").count())
          .as("records past the bounded backlog are dropped and counted, never back-pressured")
          .isGreaterThanOrEqualTo(20.0);
    } finally {
      block.countDown();
      writer.drainAndShutdown(200);
    }
  }

  @Test
  void shutdownDrainsQueuedRecordsRatherThanDroppingThem() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CompositeRejectionRepository repo = mock(CompositeRejectionRepository.class);
    // A slow but SUCCEEDING insert: a graceful drain must WAIT for these, not discard them on redeploy.
    stubInsert(
        repo,
        inv -> {
          Thread.sleep(30);
          return 1L;
        });
    CompositeRejectionWriter writer = new CompositeRejectionWriter(repo, meters);
    for (int i = 0; i < 5; i++) {
      record(writer);
    }
    writer.shutdown(); // graceful: awaits SHUTDOWN_DRAIN_MILLIS; 5 x 30ms << 5s so all drain

    verify(repo, times(5)).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    assertThat(meters.counter("ay_composite_rejection_shutdown_dropped_total").count())
        .as("a graceful drain persists every accepted record — nothing lost")
        .isZero();
  }

  @Test
  void aDrainExceedingTheTimeoutCountsTheLeftoverInsteadOfLosingItSilently()
      throws InterruptedException {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CompositeRejectionRepository repo = mock(CompositeRejectionRepository.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch block = new CountDownLatch(1);
    stubInsert(
        repo,
        inv -> {
          started.countDown();
          try {
            block.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return 1L;
        });
    CompositeRejectionWriter writer = new CompositeRejectionWriter(repo, meters);
    try {
      for (int i = 0; i < 4; i++) {
        record(writer);
      }
      assertThat(started.await(2, TimeUnit.SECONDS))
          .as("the writer thread is confirmed stuck on the first insert")
          .isTrue();
      // Task 1 is running (blocked, but it DOES honour the interrupt here); tasks 2-4 sit in the
      // queue — the drain times out and abandons them. They must be COUNTED, never silently lost.
      writer.drainAndShutdown(200);
      assertThat(meters.counter("ay_composite_rejection_shutdown_dropped_total").count())
          .as("the 3 undrained queued records are counted, not silently dropped")
          .isGreaterThanOrEqualTo(3.0);
    } finally {
      block.countDown();
    }
  }

  @Test
  void aStuckInFlightInsertThatIsTheSoleTaskIsCountedAndWarnedNeverSilentlyLost()
      throws InterruptedException {
    CompositeRejectionRepository repo = mock(CompositeRejectionRepository.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // The sole insert blocks AND ignores interruption (models a stuck native JDBC call). It is the
    // RUNNING task, never a queued one, so shutdownNow() returns an EMPTY list — without in-flight
    // accounting it would vanish at JVM exit uncounted, and with an empty queue no WARN would fire.
    stubInsert(
        repo,
        inv -> {
          started.countDown();
          while (release.getCount() > 0) {
            try {
              release.await();
            } catch (InterruptedException e) {
              // Swallow: a stuck native call does NOT honour the interrupt from shutdownNow().
            }
          }
          return 1L;
        });

    Logger writerLog = (Logger) LoggerFactory.getLogger(CompositeRejectionWriter.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    writerLog.addAppender(logs);

    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CompositeRejectionWriter writer = new CompositeRejectionWriter(repo, meters);
    try {
      record(writer); // the ONLY task — it becomes the in-flight insert, nothing sits in the queue
      assertThat(started.await(2, TimeUnit.SECONDS))
          .as("the writer thread is confirmed stuck inside the sole insert")
          .isTrue();
      // Drain times out, shutdownNow() abandons an EMPTY queue, the grace await times out on the
      // stuck insert — the loss must be COUNTED and a WARN must fire (not silent).
      writer.drainAndShutdown(200);

      assertThat(meters.counter("ay_composite_rejection_shutdown_dropped_total").count())
          .as("the stuck in-flight insert is counted, not silently lost")
          .isEqualTo(1.0);
      assertThat(logs.list)
          .as("a drain-timeout WARN fires even though the abandoned queue is empty")
          .anyMatch(
              e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("in-flight"));
    } finally {
      release.countDown();
      writerLog.detachAppender(logs);
    }
  }
}

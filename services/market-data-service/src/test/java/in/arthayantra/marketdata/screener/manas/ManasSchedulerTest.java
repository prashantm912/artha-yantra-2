package in.arthayantra.marketdata.screener.manas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The screen/bhavcopy race regression (audit H1 2026-07-05), Manas twin: the screen's PRIMARY
 * trigger is the {@code BhavcopyBackfillCompleted} event (the 19:40 cron had only accidental
 * headroom over the 19:30 backfill).
 */
class ManasSchedulerTest {

  private final ManasScreenService screener = mock(ManasScreenService.class);
  private final ManasScreenRepository repo = mock(ManasScreenRepository.class);
  private final ManasGeometryService geometry = mock(ManasGeometryService.class);
  private final in.arthayantra.marketdata.alerts.NtfyClient ntfy =
      mock(in.arthayantra.marketdata.alerts.NtfyClient.class);
  private final in.arthayantra.marketdata.ingest.IngestRunLedger ledger =
      mock(in.arthayantra.marketdata.ingest.IngestRunLedger.class);

  // ⚠️ A REAL lock, never a mock. A mocked ManasScreenLock returns false from tryLock() by default,
  // which would make every door skip. The concurrency test below would then FAIL on its own guard
  // latch (the first door never gets inside the screen, so insideScreen never counts down) — but
  // alreadyCurrentScreenIsSkippedOnTheEventAndCronPaths would PASS VACUOUSLY, because its never()
  // assertions hold exactly when nothing runs. That second test is the one a mock would quietly
  // hollow out, and it is why this is a real lock rather than a mock with stubbed returns.
  private final ManasScreenLock screenLock = new ManasScreenLock();

  @Test
  void bhavcopyCompletedEventRunsAndPersistsTheScreen() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new ManasScreenService.ScreenResult(day, 0, List.of()));

    new ManasScheduler(screener, repo, geometry, ntfy, ledger, screenLock).onBhavcopyBackfillCompleted();

    verify(repo).replaceAll(eq(day), any());
    verify(geometry).persistForPassers(eq(day), any());
  }

  @Test
  void alreadyCurrentScreenIsSkippedOnTheEventAndCronPaths() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(repo.latestScreenDate()).thenReturn(day);
    when(screener.latestScreenDate()).thenReturn(day);

    new ManasScheduler(screener, repo, geometry, ntfy, ledger, screenLock).onBhavcopyBackfillCompleted();

    org.mockito.Mockito.verify(screener, org.mockito.Mockito.never()).screen(any());
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).replaceAll(any(), any());
  }

  @Test
  void eventListenerWiringFiresTheScreenOnBhavcopyCompletion() {
    // Pins the @EventListener wiring itself (mirror of the Minervini twin's wiring test).
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new ManasScreenService.ScreenResult(day, 0, List.of()));

    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        // the class-level @ConditionalOnProperty is evaluated even for withBean registrations —
        // without the flag the bean (and its listener) is silently skipped
        .withPropertyValues("artha.manas-arora.screen.enabled=true")
        .withBean(ManasScheduler.class, () -> new ManasScheduler(screener, repo, geometry, ntfy, ledger, screenLock))
        .run(
            ctx -> {
              ctx.getSourceApplicationContext()
                  .publishEvent(
                      new in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillCompleted("job"));
              verify(repo).replaceAll(eq(day), any());
            });
  }

  /**
   * ⚠️ H13, the manas half. Same three doors, same read-then-act dedup, one cron minute after
   * minervini's. This screen has already double-run in production — {@code MANAS_SCREEN} on
   * 2026-08-11 at 18:00:05, 18:00:47 and 18:01:00, three runs inside 55 seconds — so this is a
   * regression test, not a precaution. See {@code MinerviniSchedulerTest} for the full measurement.
   */
  @Test
  void aSecondDoorArrivingMidScreenIsSkippedRatherThanRunningASecondScreen() throws Exception {
    LocalDate day = LocalDate.of(2026, 8, 11);
    java.util.concurrent.CountDownLatch insideScreen = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger screens =
        new java.util.concurrent.atomic.AtomicInteger();
    when(screener.screen(null))
        .thenAnswer(
            invocation -> {
              screens.incrementAndGet();
              insideScreen.countDown();
              release.await(5, java.util.concurrent.TimeUnit.SECONDS);
              return new ManasScreenService.ScreenResult(day, 0, List.of());
            });

    ManasScheduler scheduler = new ManasScheduler(screener, repo, geometry, ntfy, ledger, screenLock);
    Thread eventDoor = new Thread(scheduler::onBhavcopyBackfillCompleted, "event-door");
    eventDoor.start();
    org.assertj.core.api.Assertions.assertThat(
            insideScreen.await(5, java.util.concurrent.TimeUnit.SECONDS))
        .as("the event door must actually be inside the screen, or this proves nothing")
        .isTrue();

    scheduler.scheduled(); // the cron door arrives while the first screen is still running

    release.countDown();
    eventDoor.join(10_000);

    org.assertj.core.api.Assertions.assertThat(screens.get())
        .as("one screen, not two — the second door found the lock held and skipped")
        .isEqualTo(1);
    verify(repo, org.mockito.Mockito.times(1)).replaceAll(eq(day), any());
  }

  /**
   * ⚠️ The manas mirror of the minervini wait-path test, and it exists because
   * {@code ManasScheduler.acquire} is a SEPARATE method in a separate class — a revert of one is
   * completely invisible to the other. Measured in review: reverting {@code acquire()} in BOTH
   * schedulers left the whole suite green, and that run included every test in this class.
   *
   * <p>The doors are asymmetric on purpose. The cron door runs on the DEFAULT taskScheduler (pool
   * size 1, ~32 methods) where blocking would starve everything and skipping costs nothing.
   * {@code bhavcopy-complete} runs on the dedicated backfill executor and is the trigger that exists
   * to screen the FRESH watermark, so it WAITS.
   *
   * <p>⚠️ The lock is held by a SEPARATE thread, never the test thread: {@code ReentrantLock} is
   * reentrant, so a door called from the thread already holding it sails straight through and the
   * test would fail for a reason that cannot happen in production, where every door is on its own
   * thread.
   */
  @Test
  void theEventDoorWaitsForTheLockWhileTheCronDoorSkips() throws Exception {
    LocalDate day = LocalDate.of(2026, 8, 24);
    when(screener.screen(null))
        .thenReturn(new ManasScreenService.ScreenResult(day, 0, List.of()));
    ManasScheduler scheduler = new ManasScheduler(screener, repo, geometry, ntfy, ledger, screenLock);
    java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch eventFinished = new java.util.concurrent.CountDownLatch(1);

    Thread holder =
        new Thread(
            () -> {
              screenLock.lock();
              held.countDown();
              try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                screenLock.unlock();
              }
            },
            "lock-holder");
    holder.start();
    org.assertj.core.api.Assertions.assertThat(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
        .as("the holder must actually hold the lock, or this proves nothing")
        .isTrue();

    scheduler.scheduled(); // the cron door gives up at once
    org.mockito.Mockito.verify(screener, org.mockito.Mockito.never()).screen(null);

    Thread eventDoor =
        new Thread(
            () -> {
              scheduler.onBhavcopyBackfillCompleted();
              eventFinished.countDown();
            },
            "event-door");
    eventDoor.start();
    org.assertj.core.api.Assertions.assertThat(
            eventFinished.await(750, java.util.concurrent.TimeUnit.MILLISECONDS))
        .as("the bhavcopy-complete door must WAIT for the screen, never skip it")
        .isFalse();

    release.countDown();
    org.assertj.core.api.Assertions.assertThat(
            eventFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        .as("…and once the lock frees, the door it waited for must actually screen")
        .isTrue();
    eventDoor.join(5_000);
    holder.join(5_000);
    // ⚠️ The timing-free backstop: under a bare tryLock the event door SKIPS and never screens, so
    // this count fails even if the 750ms assertion above went the wrong way for a scheduling reason.
    org.mockito.Mockito.verify(screener, org.mockito.Mockito.times(1)).screen(null);
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(1)).replaceAll(eq(day), any());
  }
}

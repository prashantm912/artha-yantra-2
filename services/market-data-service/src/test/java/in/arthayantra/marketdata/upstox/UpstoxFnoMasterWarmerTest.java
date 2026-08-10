package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Locks the {@link UpstoxFnoMasterWarmer} contract: it must actually warm, it must warm OFF the
 * caller's thread, it must NEVER let a warm failure escape, and a failure storm must not multiply
 * into more than one outstanding retry.
 *
 * <p>Fail-soft is the load-bearing half. {@link UpstoxFnoMasterWarmer#warmOnStartup()} runs off
 * {@code ApplicationReadyEvent}, where an escaped exception fails service startup; {@link
 * UpstoxFnoMasterWarmer#warmPeriodically()} runs off a {@code fixedDelay} schedule, where an escaped
 * exception SUPPRESSES EVERY FUTURE EXECUTION — so one bad response from the (auth-free, but
 * down-able) Upstox CDN would silently retire the warm for the life of the process and quietly
 * restore the cold-load race this class exists to remove.
 *
 * <p>⚠️ Two of these tests drive a MOCK executor and run the captured task inline, on purpose. Once
 * the download moved onto a dedicated daemon, an {@code assertThatCode(...).doesNotThrowAnyException()}
 * around a trigger stopped proving anything at all — the throw would happen on the other thread and
 * the assertion would pass no matter what the code did. Running the captured {@link Runnable} on the
 * test thread is what keeps the no-throw claim honest.
 */
class UpstoxFnoMasterWarmerTest {

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(value);
    return p;
  }

  @Test
  void startupWarmsTheMaster() {
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    AtomicReference<String> warmThread = new AtomicReference<>();
    when(master.warm())
        .thenAnswer(
            invocation -> {
              warmThread.set(Thread.currentThread().getName());
              return true;
            });
    UpstoxFnoMasterWarmer warmer = new UpstoxFnoMasterWarmer(provider(master));

    try {
      warmer.warmOnStartup();
      await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(master).warm());
      assertThat(warmThread.get()).isNotNull();
      assertThat(warmThread.get())
          .as("the 5MB download must never run on the ApplicationReadyEvent publisher's thread")
          .isNotEqualTo(Thread.currentThread().getName());
    } finally {
      warmer.shutdown();
    }
  }

  @Test
  void theScheduledTriggerWarmsTheMasterToo() {
    // The boot warm alone is not enough: the stack runs for days, so the 12h refresh window lapses
    // mid-session and the NEXT lookup would pay the cold load on a caller's thread.
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    when(master.warm()).thenReturn(true);
    UpstoxFnoMasterWarmer warmer = new UpstoxFnoMasterWarmer(provider(master));

    try {
      // Drained between triggers ON PURPOSE. Since the queued-or-running reservation, two triggers
      // fired back-to-back COLLAPSE INTO ONE — that is the point of it, and it is pinned by
      // aTriggerArrivingWhileAWarmIsQueuedIsDroppedRatherThanStackedBehindIt below. What this test
      // is about is that a LATER scheduled tick still warms at all.
      warmer.warmPeriodically();
      await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(master, times(1)).warm());
      warmer.warmPeriodically();
      await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(master, times(2)).warm());
    } finally {
      warmer.shutdown();
    }
  }

  @Test
  void failingWarmIsSwallowedByBothTriggers() {
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    when(master.warm()).thenReturn(false);
    UpstoxFnoMasterWarmer warmer = new UpstoxFnoMasterWarmer(provider(master));

    try {
      // Drained between triggers — see theScheduledTriggerWarmsTheMasterToo for why.
      warmer.warmOnStartup();
      await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(master, times(1)).warm());
      warmer.warmPeriodically();
      await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(master, times(2)).warm());
    } finally {
      warmer.shutdown();
    }
  }

  @Test
  void failedWarmSchedulesAQuickRetry() {
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    when(master.warm()).thenReturn(false, true);
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    UpstoxFnoMasterWarmer warmer =
        new UpstoxFnoMasterWarmer(provider(master), executor, Duration.ofMillis(1));

    try {
      warmer.warmPeriodically();
      await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(master, times(2)).warm());
    } finally {
      warmer.shutdown();
    }
  }

  @Test
  void twoFailuresLeaveOnlyOneRetryOutstanding() {
    // Without the single-flight guard, EVERY failed periodic warm starts its own perpetual 5-minute
    // retry chain. The chains never coalesce, so a multi-day CDN outage adds one more chain per
    // period until the single warm thread is saturated by attempts that each cost up to 75s. Two
    // failed triggers must therefore leave exactly ONE scheduled retry, not two.
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    when(master.warm()).thenReturn(false);
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    UpstoxFnoMasterWarmer warmer =
        new UpstoxFnoMasterWarmer(provider(master), executor, Duration.ofMinutes(5));
    ArgumentCaptor<Runnable> submitted = ArgumentCaptor.forClass(Runnable.class);

    // Each submission must be DRAINED before the next, or the queued-or-running reservation drops
    // the second one — which is the subject of the sibling test below, not this one.
    warmer.warmOnStartup();
    verify(executor, times(1)).execute(submitted.capture());
    submitted.getValue().run(); // first warm fails on this thread, schedules the retry

    warmer.warmPeriodically();
    verify(executor, times(2)).execute(submitted.capture());
    submitted.getValue().run(); // second warm fails too

    verify(executor, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
  }

  /**
   * A second trigger arriving while a warm is QUEUED OR RUNNING is dropped, not stacked behind it.
   *
   * <p>This is the test the first attempt at the fix did not have, and it would have failed it.
   * That version took the reservation inside the task body — useless, because {@code warmExecutor}
   * is single-threaded, so queued tasks never overlap and each one in turn found the flag clear and
   * performed another full download. The reservation has to mean "queued OR running", so it is
   * taken before {@code execute}.
   *
   * <p>What it protects: Spring's {@code fixedDelay} measures the DISPATCH, and submit returns the
   * instant it hands off. At the one-minute floor a warm is therefore enqueued every minute however
   * long the current 5MB+ download is taking, and the backlog is duplicate downloads against a
   * shared rate limiter.
   */
  @Test
  void aTriggerArrivingWhileAWarmIsQueuedIsDroppedRatherThanStackedBehindIt() {
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    when(master.warm()).thenReturn(true);
    // Never runs what it is given — this IS the "already queued" condition.
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    UpstoxFnoMasterWarmer warmer =
        new UpstoxFnoMasterWarmer(provider(master), executor, Duration.ofMinutes(5));

    warmer.warmOnStartup();
    warmer.warmPeriodically();
    warmer.warmPeriodically();
    warmer.warmPeriodically();

    verify(executor, times(1)).execute(any(Runnable.class));
    verify(master, never()).warm(); // nothing ran: the queue holds exactly one task, not four
  }

  /** …and once that warm completes, the NEXT trigger is accepted again. */
  @Test
  void theReservationIsReleasedWhenTheWarmFinishes() {
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    when(master.warm()).thenReturn(true);
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    UpstoxFnoMasterWarmer warmer =
        new UpstoxFnoMasterWarmer(provider(master), executor, Duration.ofMinutes(5));
    ArgumentCaptor<Runnable> submitted = ArgumentCaptor.forClass(Runnable.class);

    warmer.warmOnStartup();
    verify(executor, times(1)).execute(submitted.capture());
    submitted.getValue().run(); // the warm completes

    warmer.warmPeriodically();

    verify(executor, times(2)).execute(any(Runnable.class));
  }

  @Test
  void absentMasterDoesNothingRatherThanFail() {
    // The client is bound only when the analytics/quote/ticker flags select it; without one there is
    // simply nothing to warm, which must not throw out of startup or the scheduler — and must not
    // book a retry either, since there is no failure to recover from.
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    UpstoxFnoMasterWarmer warmer =
        new UpstoxFnoMasterWarmer(
            provider((UpstoxFnoMasterClient) null), executor, Duration.ofMinutes(5));
    ArgumentCaptor<Runnable> submitted = ArgumentCaptor.forClass(Runnable.class);

    // One at a time: the queued-or-running reservation drops a second trigger while the first is
    // still outstanding, so both triggers only reach the executor if each is drained first.
    warmer.warmOnStartup();
    verify(executor, times(1)).execute(submitted.capture());
    assertThatCode(submitted.getValue()::run)
        .as("run on THIS thread, so an escaped exception actually fails the test")
        .doesNotThrowAnyException();

    warmer.warmPeriodically();
    verify(executor, times(2)).execute(submitted.capture());
    assertThatCode(submitted.getValue()::run).doesNotThrowAnyException();

    verify(executor, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
  }

  @Test
  void theDefaultWarmIntervalStaysStrictlyInsideTheClientsRefreshWindow() throws Exception {
    // The whole design rests on this inequality: warm more often than the cache expires, so the
    // client's lazy branch — the one that downloads 5MB on the CALLER's thread — never fires in a
    // long-running service. Widening the interval past REFRESH would silently reinstate the race.
    Scheduled scheduled =
        UpstoxFnoMasterWarmer.class.getMethod("warmPeriodically").getAnnotation(Scheduled.class);
    assertThat(scheduled).isNotNull();

    assertThat(scheduled.scheduler()).isEmpty();
    assertThat(scheduled.fixedDelayString()).contains("upstoxFnoMasterWarmInterval");
    // The ceiling leaves 5 minutes, not 1: fixedDelay counts from the previous run's COMPLETION but
    // the client's window is measured from its START, so the margin has to clear one worst-case
    // download (connect 15s + read 60s) or a slow success still lets the window lapse.
    assertThat(UpstoxFnoMasterWarmer.clampWarmInterval(Duration.ofHours(18)))
        .isEqualTo(UpstoxFnoMasterClient.REFRESH.minusMinutes(5));
    assertThat(UpstoxFnoMasterWarmer.clampWarmInterval(Duration.ofHours(18)))
        .isLessThan(UpstoxFnoMasterClient.REFRESH);
    assertThat(UpstoxFnoMasterWarmer.clampWarmInterval(Duration.ofSeconds(30)))
        .isEqualTo(Duration.ofMinutes(1));
    // initialDelay must match the period, else the scheduler's first run duplicates the startup warm.
    assertThat(scheduled.initialDelayString()).isEqualTo(scheduled.fixedDelayString());
  }

  @Test
  void readyListenerIsOrderedLast() throws Exception {
    // Annotation-only. The off-thread claim this listener also carries is proved by
    // startupWarmsTheMaster, which compares the warming thread's name against the caller's.
    EventListener listener =
        UpstoxFnoMasterWarmer.class.getMethod("warmOnStartup").getAnnotation(EventListener.class);
    Order order = UpstoxFnoMasterWarmer.class.getMethod("warmOnStartup").getAnnotation(Order.class);

    assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    assertThat(order.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }
}

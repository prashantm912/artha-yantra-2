package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Locks the {@link UpstoxFnoMasterWarmer} contract: it must actually warm, and it must NEVER let a
 * warm failure escape.
 *
 * <p>Fail-soft is the load-bearing half. {@link UpstoxFnoMasterWarmer#warmOnStartup()} runs off
 * {@code ApplicationReadyEvent}, where an escaped exception fails service startup; {@link
 * UpstoxFnoMasterWarmer#warmPeriodically()} runs off a {@code fixedDelay} schedule, where an escaped
 * exception SUPPRESSES EVERY FUTURE EXECUTION — so one bad response from the (auth-free, but
 * down-able) Upstox CDN would silently retire the warm for the life of the process and quietly
 * restore the cold-load race this class exists to remove.
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
    UpstoxFnoMasterWarmer warmer = new UpstoxFnoMasterWarmer(provider(master), Duration.ofHours(6));

    try {
      warmer.warmOnStartup();
      await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(master).warm());
      assertThat(warmThread.get()).isNotNull();
      assertThat(warmThread.get()).isNotEqualTo(Thread.currentThread().getName());
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
    UpstoxFnoMasterWarmer warmer = new UpstoxFnoMasterWarmer(provider(master), Duration.ofHours(6));

    try {
      warmer.warmPeriodically();
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
    UpstoxFnoMasterWarmer warmer = new UpstoxFnoMasterWarmer(provider(master), Duration.ofHours(6));

    try {
      warmer.warmOnStartup();
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
        new UpstoxFnoMasterWarmer(
            provider(master), Duration.ofHours(6), executor, Duration.ofMillis(1));

    try {
      warmer.warmPeriodically();
      await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(master, times(2)).warm());
    } finally {
      warmer.shutdown();
    }
  }

  @Test
  void absentMasterDoesNothingRatherThanFail() {
    // The client is bound only when the analytics/quote/ticker flags select it; without one there is
    // simply nothing to warm, which must not throw out of startup or the scheduler.
    UpstoxFnoMasterWarmer warmer =
        new UpstoxFnoMasterWarmer(
            provider((UpstoxFnoMasterClient) null), Duration.ofHours(6));

    try {
      warmer.warmOnStartup();
      warmer.warmPeriodically();
    } finally {
      warmer.shutdown();
    }
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
    assertThat(UpstoxFnoMasterWarmer.clampWarmInterval(Duration.ofHours(18)))
        .isEqualTo(UpstoxFnoMasterClient.REFRESH.minusMinutes(1));
    assertThat(UpstoxFnoMasterWarmer.clampWarmInterval(Duration.ofSeconds(30)))
        .isEqualTo(Duration.ofMinutes(1));
    // initialDelay must match the period, else the scheduler's first run duplicates the startup warm.
    assertThat(scheduled.initialDelayString()).isEqualTo(scheduled.fixedDelayString());
  }

  @Test
  void readyListenerIsOrderedLastAndRunsOffTheCallerThread() throws Exception {
    EventListener listener =
        UpstoxFnoMasterWarmer.class.getMethod("warmOnStartup").getAnnotation(EventListener.class);
    Order order = UpstoxFnoMasterWarmer.class.getMethod("warmOnStartup").getAnnotation(Order.class);

    assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    assertThat(order.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }
}

package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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

    new UpstoxFnoMasterWarmer(provider(master)).warmOnStartup();

    verify(master).warm();
  }

  @Test
  void theScheduledTriggerWarmsTheMasterToo() {
    // The boot warm alone is not enough: the stack runs for days, so the 12h refresh window lapses
    // mid-session and the NEXT lookup would pay the cold load on a caller's thread.
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    UpstoxFnoMasterWarmer warmer = new UpstoxFnoMasterWarmer(provider(master));

    warmer.warmPeriodically();
    warmer.warmPeriodically();

    verify(master, times(2)).warm();
  }

  @Test
  void failingWarmIsSwallowedByBothTriggers() {
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    doThrow(new IllegalStateException("CDN down")).when(master).warm();
    UpstoxFnoMasterWarmer warmer = new UpstoxFnoMasterWarmer(provider(master));

    assertThatCode(warmer::warmOnStartup)
        .as("an escaped exception here would fail service startup")
        .doesNotThrowAnyException();
    assertThatCode(warmer::warmPeriodically)
        .as("an escaped exception here would cancel every future scheduled warm")
        .doesNotThrowAnyException();

    // ...and the failure did not stop it trying again — the task is still live.
    verify(master, times(2)).warm();
  }

  @Test
  void absentMasterDoesNothingRatherThanFail() {
    // The client is bound only when the analytics/quote/ticker flags select it; without one there is
    // simply nothing to warm, which must not throw out of startup or the scheduler.
    UpstoxFnoMasterWarmer warmer = new UpstoxFnoMasterWarmer(provider(null));

    assertThatCode(warmer::warmOnStartup).doesNotThrowAnyException();
    assertThatCode(warmer::warmPeriodically).doesNotThrowAnyException();
  }

  @Test
  void theDefaultWarmIntervalStaysStrictlyInsideTheClientsRefreshWindow() throws Exception {
    // The whole design rests on this inequality: warm more often than the cache expires, so the
    // client's lazy branch — the one that downloads 5MB on the CALLER's thread — never fires in a
    // long-running service. Widening the interval past REFRESH would silently reinstate the race.
    Scheduled scheduled =
        UpstoxFnoMasterWarmer.class.getMethod("warmPeriodically").getAnnotation(Scheduled.class);
    assertThat(scheduled).isNotNull();

    Duration interval = defaultOf(scheduled.fixedDelayString());

    assertThat(interval).isLessThan(UpstoxFnoMasterClient.REFRESH);
    // initialDelay must match the period, else the scheduler's first run duplicates the startup warm.
    assertThat(defaultOf(scheduled.initialDelayString())).isEqualTo(interval);
  }

  /** Extracts the default from a {@code ${property:default}} placeholder. */
  private static Duration defaultOf(String placeholder) {
    return Duration.parse(placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1));
  }
}

package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Audit V3 stale-tick instrumentation: the bracket-starvation counter + once-per-(position, day) ntfy
 * and the settle-refusal counter/alert. Drives a hand-advanced {@link Clock} so the starvation-duration
 * and IST-day dedup are deterministic. No Spring context — pure collaborator behaviour.
 */
class PaperStaleTickAlerterTest {

  /** A clock the test advances by hand (single instance so the alerter sees the advances). */
  private static final class TickingClock extends Clock {
    private Instant now;

    TickingClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  private TickingClock clock;
  private NotifierClient notifier;
  private SimpleMeterRegistry meters;
  private PaperStaleTickAlerter alerter;

  private static PositionRow pos(long id) {
    return new PositionRow(
        id, "NFO", "NIFTY26JUL25000CE", "BUY", 75, new BigDecimal("100"), BigDecimal.ZERO, "OPEN",
        null, null, null, new BigDecimal("90"), new BigDecimal("120"), "a3unit");
  }

  private double bracketCount() {
    return meters.counter("ay_paper_bracket_starved_total").count();
  }

  private double settleCount() {
    return meters.counter("ay_paper_settle_refused_total").count();
  }

  @BeforeEach
  void setUp() {
    // 2026-07-11 05:00Z == 10:30 IST — advancing a few minutes never crosses the IST day boundary.
    clock = new TickingClock(Instant.parse("2026-07-11T05:00:00Z"));
    notifier = Mockito.mock(NotifierClient.class);
    when(notifier.configured("NTFY")).thenReturn(true);
    meters = new SimpleMeterRegistry();
    alerter = new PaperStaleTickAlerter(notifier, clock, meters, 2); // 2-minute alert threshold
  }

  @Test
  void absentTickCountsEveryPassButAlertsOncePerDayAfterTheThreshold() {
    PositionRow p = pos(1L);

    // Pass 1: starved but only just — counts, no alert yet (0s < 2min).
    alerter.observeBracket(p, Optional.empty());
    assertThat(bracketCount()).isEqualTo(1.0);
    verify(notifier, never()).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());

    // 3 minutes later, still starved: counts again AND now alerts (3min >= 2min).
    clock.advance(Duration.ofMinutes(3));
    alerter.observeBracket(p, Optional.empty());
    assertThat(bracketCount()).isEqualTo(2.0);
    verify(notifier, times(1)).send(Mockito.eq("NTFY"), Mockito.anyString(), Mockito.anyString());

    // A further starved pass the SAME day: counts, but the alert is deduped (still one send).
    clock.advance(Duration.ofMinutes(1));
    alerter.observeBracket(p, Optional.empty());
    assertThat(bracketCount()).isEqualTo(3.0);
    verify(notifier, times(1)).send(Mockito.eq("NTFY"), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void freshTickIsHealthy_noCountNoAlert_andClearsTheStarvationWindow() {
    PositionRow p = pos(2L);

    // Build up a starved window and trip the alert.
    alerter.observeBracket(p, Optional.empty());
    clock.advance(Duration.ofMinutes(3));
    alerter.observeBracket(p, Optional.empty());
    verify(notifier, times(1)).send(Mockito.eq("NTFY"), Mockito.anyString(), Mockito.anyString());
    double countedSoFar = bracketCount();

    // A fresh tick (5s old, threshold 2min) is healthy: no counter increment, no alert, window cleared.
    alerter.observeBracket(p, Optional.of(new LastTickReader.TickView(new BigDecimal("101"), Duration.ofSeconds(5))));
    assertThat(bracketCount()).isEqualTo(countedSoFar);
    verify(notifier, times(1)).send(Mockito.eq("NTFY"), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void presentButStaleTickAlertsImmediatelyUsingItsOwnAge() {
    // A dead-but-present tick carries age = how long it has been frozen; 5min > 2min alerts on pass 1.
    alerter.observeBracket(
        pos(3L), Optional.of(new LastTickReader.TickView(new BigDecimal("100"), Duration.ofMinutes(5))));
    assertThat(bracketCount()).isEqualTo(1.0);
    verify(notifier, times(1)).send(Mockito.eq("NTFY"), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void settleRefusedCountsAndAlertsOncePerPositionPerDay() {
    PositionRow p = pos(4L);

    alerter.settleRefused(p, "INTRADAY_MTM");
    assertThat(settleCount()).isEqualTo(1.0);
    verify(notifier, times(1)).send(Mockito.eq("NTFY"), Mockito.anyString(), Mockito.anyString());

    // Same position, same day: counts again but the alert dedups.
    alerter.settleRefused(p, "MANUAL");
    assertThat(settleCount()).isEqualTo(2.0);
    verify(notifier, times(1)).send(Mockito.eq("NTFY"), Mockito.anyString(), Mockito.anyString());

    // A DIFFERENT position alerts on its own.
    alerter.settleRefused(pos(5L), "INTRADAY_MTM");
    verify(notifier, times(2)).send(Mockito.eq("NTFY"), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void ntfyNotConfigured_neverSends_butStillCounts() {
    when(notifier.configured("NTFY")).thenReturn(false);
    alerter.settleRefused(pos(6L), "INTRADAY_MTM");
    assertThat(settleCount()).isEqualTo(1.0);
    verify(notifier, never()).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
  }
}

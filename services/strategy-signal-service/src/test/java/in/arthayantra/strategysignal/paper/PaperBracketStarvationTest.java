package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Audit V3 at the {@link PaperBracketEvaluator} level: the starvation instrumentation fires without
 * changing WHAT the bracket decides. A breaching tick still closes; an absent tick still leaves the
 * position open (now visibly, via {@link PaperStaleTickAlerter}); a fresh in-band tick is healthy.
 */
class PaperBracketStarvationTest {

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

  private PaperPositionRepository positions;
  private LastTickReader lastTick;
  private PaperService paper;
  private NotifierClient notifier;
  private SimpleMeterRegistry meters;
  private TickingClock clock;
  private PaperStaleTickAlerter alerter;
  private PaperBracketEvaluator evaluator;

  private static PositionRow buyPos(String sl, String tp) {
    return new PositionRow(
        7L, "NFO", "NIFTY26JUL25000CE", "BUY", 75, new BigDecimal("100"), BigDecimal.ZERO, "OPEN",
        null, null, null, sl == null ? null : new BigDecimal(sl), tp == null ? null : new BigDecimal(tp),
        "a3unit");
  }

  @BeforeEach
  void setUp() {
    positions = Mockito.mock(PaperPositionRepository.class);
    lastTick = Mockito.mock(LastTickReader.class);
    paper = Mockito.mock(PaperService.class);
    notifier = Mockito.mock(NotifierClient.class);
    when(notifier.configured("NTFY")).thenReturn(true);
    meters = new SimpleMeterRegistry();
    clock = new TickingClock(Instant.parse("2026-07-11T05:00:00Z"));
    alerter = new PaperStaleTickAlerter(notifier, clock, meters, 2, "minervini,manas-arora");
    evaluator = new PaperBracketEvaluator(positions, lastTick, paper, alerter);
  }

  @Test
  void breachingTickStillClosesAndNeverAlerts() {
    PositionRow p = buyPos("90", "120");
    when(positions.listOpen()).thenReturn(List.of(p));
    when(lastTick.lastTick("NFO", "NIFTY26JUL25000CE"))
        .thenReturn(Optional.of(new LastTickReader.TickView(new BigDecimal("85"), Duration.ofSeconds(1))));

    int closed = evaluator.evaluate();

    assertThat(closed).isEqualTo(1);
    verify(paper).settle(eq(p), eq(new BigDecimal("85")), eq("STOP_LOSS"));
    verify(notifier, never()).send(anyString(), anyString(), anyString());
    assertThat(meters.counter("ay_paper_bracket_starved_total").count()).isZero();
  }

  @Test
  void absentTickNeverClosesButAlertsAfterTheThreshold() {
    PositionRow p = buyPos("90", "120");
    when(positions.listOpen()).thenReturn(List.of(p));
    when(lastTick.lastTick("NFO", "NIFTY26JUL25000CE")).thenReturn(Optional.empty());

    evaluator.evaluate(); // pass 1 — starved, no alert yet
    clock.advance(Duration.ofMinutes(3));
    int closed = evaluator.evaluate(); // pass 2 — 3min starved, alert fires

    assertThat(closed).isZero();
    verify(paper, never()).settle(any(), any(), anyString());
    verify(notifier, times(1)).send(eq("NTFY"), anyString(), anyString());
    assertThat(meters.counter("ay_paper_bracket_starved_total").count()).isEqualTo(2.0);
  }

  @Test
  void freshInBandTickIsHealthy_noCloseNoAlert() {
    PositionRow p = buyPos("90", "120");
    when(positions.listOpen()).thenReturn(List.of(p));
    when(lastTick.lastTick("NFO", "NIFTY26JUL25000CE"))
        .thenReturn(Optional.of(new LastTickReader.TickView(new BigDecimal("105"), Duration.ofSeconds(1))));

    int closed = evaluator.evaluate();

    assertThat(closed).isZero();
    verify(paper, never()).settle(any(), any(), anyString());
    verify(notifier, never()).send(anyString(), anyString(), anyString());
    assertThat(meters.counter("ay_paper_bracket_starved_total").count()).isZero();
  }
}

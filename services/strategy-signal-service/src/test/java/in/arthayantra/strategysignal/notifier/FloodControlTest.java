package in.arthayantra.strategysignal.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.notifier.FloodControl.Decision;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Phase 41 flood control: cooldown dedup + hourly cap + the one-shot "N suppressed" summary. */
class FloodControlTest {

  /** A clock the test advances. */
  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-02-03T09:15:00Z");

    void advanceSeconds(long s) {
      now = now.plusSeconds(s);
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

  @Test
  void cooldownSuppressesARepeatWithinTheWindow() {
    MovableClock clock = new MovableClock();
    FloodControl flood = new FloodControl(60, 100, clock);
    UUID a = UUID.randomUUID();

    assertThat(flood.admit(a)).isEqualTo(Decision.ALLOW);
    assertThat(flood.admit(a)).isEqualTo(Decision.COOLDOWN); // immediate repeat
    clock.advanceSeconds(61);
    assertThat(flood.admit(a)).isEqualTo(Decision.ALLOW); // cooldown elapsed
  }

  @Test
  void hourlyCapSuppressesAndEmitsOneSummary() {
    MovableClock clock = new MovableClock();
    FloodControl flood = new FloodControl(0, 2, clock); // no cooldown, cap 2

    assertThat(flood.admit(UUID.randomUUID())).isEqualTo(Decision.ALLOW);
    assertThat(flood.admit(UUID.randomUUID())).isEqualTo(Decision.ALLOW);
    assertThat(flood.admit(UUID.randomUUID())).isEqualTo(Decision.CAPPED);
    assertThat(flood.admit(UUID.randomUUID())).isEqualTo(Decision.CAPPED);

    assertThat(flood.capSummaryDue()).isEqualTo(2); // both cap-suppressions, once
    assertThat(flood.capSummaryDue()).isZero(); // not repeated this hour
  }

  @Test
  void theHourResets() {
    MovableClock clock = new MovableClock();
    FloodControl flood = new FloodControl(0, 1, clock);
    assertThat(flood.admit(UUID.randomUUID())).isEqualTo(Decision.ALLOW);
    assertThat(flood.admit(UUID.randomUUID())).isEqualTo(Decision.CAPPED);
    clock.advanceSeconds(3601); // > 1 hour
    assertThat(flood.admit(UUID.randomUUID())).isEqualTo(Decision.ALLOW); // window reset
  }
}

package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.feed.NormalizedTick;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** F4 state bookkeeping: wall-clock stamps + the drill-suppression fault hook. */
class DataHealthStateTest {

  private static final Instant NOW = Instant.parse("2026-06-10T07:10:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private static NormalizedTick tick(String symbol) {
    // the exchange stamp is deliberately absurd: the state must record ARRIVAL wall time,
    // never trust the frame's own timestamp (the 2026-07-03 mis-stamped-frame lesson)
    return new NormalizedTick(
        "NSE", symbol, new BigDecimal("1.00"), 1, null,
        OffsetDateTime.parse("2031-01-01T00:00:00+05:30"), 1);
  }

  @Test
  void ticksStampArrivalWallTimeAndFirstTickIsSticky() {
    DataHealthState state = new DataHealthState(clock, "");
    state.onNormalizedTick(tick("TEST"));
    assertThat(state.lastTicks()).containsEntry("NSE:TEST", NOW);
    assertThat(state.firstTick("NSE:TEST")).isEqualTo(NOW);
  }

  @Test
  void drillSuppressKeySimulatesADeadBarPublisher() {
    DataHealthState state = new DataHealthState(clock, "NSE:TEST");
    state.recordBar("NSE", "TEST");
    state.recordBar("NSE", "OTHER");
    assertThat(state.lastBar("NSE:TEST")).isNull();
    assertThat(state.lastBar("NSE:OTHER")).isEqualTo(NOW);
  }
}

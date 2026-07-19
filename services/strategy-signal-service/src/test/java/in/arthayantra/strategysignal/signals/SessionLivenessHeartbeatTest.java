package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import in.arthayantra.common.web.time.Ist;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * The session-cadence dead-man's-switch: pings an external monitor every ~10 min DURING the live
 * session, but only while candles are arriving. The monitor alarms when a ping is ABSENT (the session
 * was silently lost). Verifies the receive-liveness gate, the [09:30,15:20) IST window (narrower than
 * the calendar session), the holiday/weekend guard, the arming gate, and the fail-soft contract.
 *
 * <p>Pure unit test — no Spring context, no Testcontainers. The health decision is exercised through
 * the parameterized {@code healthy}/{@code pingIfHealthy} seams (real bundled NSE calendar, fixed
 * dates), and {@code send} is overridden to capture the ping without network I/O. Engine + clock are
 * unused by those seams, so they are passed null.
 */
class SessionLivenessHeartbeatTest {

  private static final String URL = "https://hc-ping.com/session-abc-123";
  private static final long BAR_GAP_MS = 180_000; // matches the subscriber-watchdog default (3 min)
  private static final long NOW_MS = 1_700_000_000_000L;

  // Known bundled-calendar dates (libs/market-calendar/nse-trading-holidays.csv):
  private static final ZonedDateTime TRADING_NOON =
      ZonedDateTime.of(2026, 7, 17, 12, 0, 0, 0, Ist.ZONE); // Fri, normal trading day, mid-session
  private static final ZonedDateTime TRADING_0925 =
      ZonedDateTime.of(2026, 7, 17, 9, 25, 0, 0, Ist.ZONE); // open per calendar (>=09:15) but <09:30
  private static final ZonedDateTime TRADING_1525 =
      ZonedDateTime.of(2026, 7, 17, 15, 25, 0, 0, Ist.ZONE); // open per calendar (<15:30) but >=15:20
  private static final ZonedDateTime HOLIDAY_NOON =
      ZonedDateTime.of(2026, 1, 26, 12, 0, 0, 0, Ist.ZONE); // Republic Day (a weekday holiday)
  private static final ZonedDateTime WEEKEND_NOON =
      ZonedDateTime.of(2026, 7, 18, 12, 0, 0, 0, Ist.ZONE); // Saturday

  /** Captures the ping target instead of hitting the network (send() is package-private). */
  private static final class Recording extends SessionLivenessHeartbeat {
    private final boolean fail;
    private String pinged;
    private int calls;

    Recording(String url) {
      this(url, false);
    }

    Recording(String url, boolean fail) {
      super(url, null, null, BAR_GAP_MS); // engine + clock are unused by the seams under test
      this.fail = fail;
    }

    @Override
    void send(String pingUrl) throws Exception {
      calls++;
      if (fail) {
        throw new java.io.IOException("simulated ping failure");
      }
      this.pinged = pingUrl;
    }
  }

  @Test
  void pingsWhenBarsFreshInSessionOnTradingDay() {
    Recording heartbeat = new Recording(URL);
    heartbeat.pingIfHealthy(NOW_MS, NOW_MS - 60_000, TRADING_NOON); // last bar 1 min ago (< 3 min gap)
    assertThat(heartbeat.calls).isEqualTo(1);
    assertThat(heartbeat.pinged).isEqualTo(URL);
  }

  @Test
  void withholdsWhenBarsStale() {
    Recording heartbeat = new Recording(URL);
    heartbeat.pingIfHealthy(NOW_MS, NOW_MS - 300_000, TRADING_NOON); // 5 min gap >= 3 min → stale
    assertThat(heartbeat.calls).isZero(); // WITHHELD — the missed ping is the alarm
  }

  @Test
  void withholdsBeforeWindowOpens() {
    Recording heartbeat = new Recording(URL);
    heartbeat.pingIfHealthy(NOW_MS, NOW_MS - 10_000, TRADING_0925); // fresh bars, but before 09:30
    assertThat(heartbeat.calls).isZero();
  }

  @Test
  void withholdsAfterWindowCloses() {
    Recording heartbeat = new Recording(URL);
    heartbeat.pingIfHealthy(NOW_MS, NOW_MS - 10_000, TRADING_1525); // fresh bars, but at/after 15:20
    assertThat(heartbeat.calls).isZero();
  }

  @Test
  void withholdsOnAHoliday() {
    Recording heartbeat = new Recording(URL);
    heartbeat.pingIfHealthy(NOW_MS, NOW_MS - 10_000, HOLIDAY_NOON); // NSE closed → WITHHELD
    assertThat(heartbeat.calls).isZero();
  }

  @Test
  void withholdsOnAWeekend() {
    Recording heartbeat = new Recording(URL);
    heartbeat.pingIfHealthy(NOW_MS, NOW_MS - 10_000, WEEKEND_NOON); // Saturday → WITHHELD
    assertThat(heartbeat.calls).isZero();
  }

  @Test
  void beatNoOpsWhenUnarmed() {
    Recording heartbeat = new Recording("  "); // blank url → dormant even if the bean loaded
    assertThatCode(heartbeat::beat).doesNotThrowAnyException(); // never touches the null engine/clock
    assertThat(heartbeat.calls).isZero();
  }

  @Test
  void pingFailureNeverPropagates() {
    Recording heartbeat = new Recording(URL, true);
    // A network/monitor failure must never propagate — the external monitor's own missed-ping alert
    // is the backstop.
    assertThatCode(() -> heartbeat.pingIfHealthy(NOW_MS, NOW_MS - 60_000, TRADING_NOON))
        .doesNotThrowAnyException();
    assertThat(heartbeat.calls).isEqualTo(1); // send was attempted, the failure swallowed
  }

  @Test
  void healthyIsTrueOnlyWithFreshBarsInsideAnOpenSession() {
    Recording heartbeat = new Recording(URL);
    assertThat(heartbeat.healthy(NOW_MS, NOW_MS - 60_000, TRADING_NOON)).isTrue();
    assertThat(heartbeat.healthy(NOW_MS, NOW_MS - 300_000, TRADING_NOON)).isFalse(); // stale bars
    assertThat(heartbeat.healthy(NOW_MS, NOW_MS - 60_000, WEEKEND_NOON)).isFalse(); // closed session
  }
}

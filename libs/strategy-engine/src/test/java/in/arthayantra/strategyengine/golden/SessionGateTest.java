package in.arthayantra.strategyengine.golden;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategyengine.config.StrategyDefinition.Session;
import in.arthayantra.strategyengine.golden.TickwiseGoldenRunner.SessionGate;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * The replay session gate (mirrors the live SignalEngine window/square-off enforcement): entries
 * only inside the window and before square_off; a tighter expiry-day window; and fully inert when no
 * session fields are declared (the property that keeps the golden vectors byte-identical).
 */
class SessionGateTest {

  private static Session session(
      String from, String to, String squareOff, Boolean expiryAllowed, String expFrom, String expTo) {
    return new Session(
        "intraday", from, to, squareOff, "15:20", "next_open", false, expiryAllowed, expFrom, expTo);
  }

  /** A weekday that is NOT a weekly index expiry (so the normal window applies). */
  private static LocalDate nonExpiryDay(MarketCalendar cal) {
    LocalDate d = LocalDate.of(2026, 4, 6); // a Monday
    while (cal.isWeeklyIndexExpiryDay(d) || !cal.isTradingDay(d)) {
      d = d.plusDays(1);
    }
    return d;
  }

  @Test
  void blocksEntriesOutsideTheWindowAndForcesSquareOff() {
    MarketCalendar cal = MarketCalendar.nse();
    LocalDate day = nonExpiryDay(cal);
    SessionGate g = new SessionGate(session("09:20", "15:00", "15:12", null, null, null));

    assertThat(g.entryAllowed(LocalTime.of(9, 19), day)).isFalse(); // before window open
    assertThat(g.entryAllowed(LocalTime.of(9, 20), day)).isTrue(); // window open is inclusive
    assertThat(g.entryAllowed(LocalTime.of(14, 59), day)).isTrue();
    assertThat(g.entryAllowed(LocalTime.of(15, 0), day)).isFalse(); // window close is exclusive
    assertThat(g.entryAllowed(LocalTime.of(15, 29), day)).isFalse(); // the blowup time — blocked

    assertThat(g.pastSquareOff(LocalTime.of(15, 11))).isFalse();
    assertThat(g.pastSquareOff(LocalTime.of(15, 12))).isTrue(); // square_off is inclusive
  }

  @Test
  void noSessionFieldsMeansEverythingAllowed() {
    // the golden-fixture shape (only style/pre_close_at) → gate is a no-op, parity preserved.
    SessionGate g = new SessionGate(session(null, null, null, null, null, null));
    LocalDate anyDay = LocalDate.of(2026, 4, 6);
    assertThat(g.entryAllowed(LocalTime.of(9, 0), anyDay)).isTrue();
    assertThat(g.entryAllowed(LocalTime.of(15, 29), anyDay)).isTrue();
    assertThat(g.pastSquareOff(LocalTime.of(15, 29))).isFalse();
  }

  @Test
  void expiryDayUsesTheTighterWindow() {
    MarketCalendar cal = MarketCalendar.nse();
    LocalDate expiry = null;
    for (LocalDate d = LocalDate.of(2026, 4, 1); d.isBefore(LocalDate.of(2026, 5, 1)); d = d.plusDays(1)) {
      if (cal.isWeeklyIndexExpiryDay(d)) {
        expiry = d;
        break;
      }
    }
    assertThat(expiry).as("a weekly index expiry in April 2026").isNotNull();

    SessionGate g = new SessionGate(session("09:20", "15:00", "15:12", true, "09:20", "14:30"));
    // 14:45 is inside the normal 15:00 window but past the 14:30 expiry-day window.
    assertThat(g.entryAllowed(LocalTime.of(14, 45), expiry)).isFalse();
    assertThat(g.entryAllowed(LocalTime.of(14, 29), expiry)).isTrue();
    assertThat(g.entryAllowed(LocalTime.of(14, 45), nonExpiryDay(cal))).isTrue(); // normal day unaffected
  }

  @Test
  void expiryDayDisallowedBlocksTheWholeDay() {
    MarketCalendar cal = MarketCalendar.nse();
    LocalDate expiry = null;
    for (LocalDate d = LocalDate.of(2026, 4, 1); d.isBefore(LocalDate.of(2026, 5, 1)); d = d.plusDays(1)) {
      if (cal.isWeeklyIndexExpiryDay(d)) {
        expiry = d;
        break;
      }
    }
    SessionGate g = new SessionGate(session("09:20", "15:00", "15:12", false, null, null));
    assertThat(g.entryAllowed(LocalTime.of(10, 0), expiry)).isFalse(); // no entries on expiry day
    assertThat(g.entryAllowed(LocalTime.of(10, 0), nonExpiryDay(cal))).isTrue();
  }
}

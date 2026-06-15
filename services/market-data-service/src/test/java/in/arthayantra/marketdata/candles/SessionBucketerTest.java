package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** Phase-10 bucket flooring: 09:15 open clamp, 15:30 close clamp, day-boundary awareness. */
class SessionBucketerTest {

  private final SessionBucketer bucketer = new SessionBucketer(MarketCalendar.nse());

  private static OffsetDateTime ist(String text) {
    return OffsetDateTime.parse(text + "+05:30");
  }

  @Test
  void inSessionTicksFloorToTheirMinute() {
    assertThat(bucketer.bucketFor(ist("2026-06-10T10:23:45"))).isEqualTo(ist("2026-06-10T10:23:00"));
    assertThat(bucketer.bucketFor(ist("2026-06-10T09:15:00"))).isEqualTo(ist("2026-06-10T09:15:00"));
  }

  @Test
  void preOpenPrintsFoldIntoTheOpenBucket() {
    assertThat(bucketer.bucketFor(ist("2026-06-10T09:08:30"))).isEqualTo(ist("2026-06-10T09:15:00"));
  }

  @Test
  void atOrAfterCloseFoldsIntoTheLastSessionBucket() {
    assertThat(bucketer.bucketFor(ist("2026-06-10T15:30:00"))).isEqualTo(ist("2026-06-10T15:29:00"));
    assertThat(bucketer.bucketFor(ist("2026-06-10T15:45:10"))).isEqualTo(ist("2026-06-10T15:29:00"));
  }

  @Test
  void nonTradingTimesUsePlainMinuteFlooring() {
    // Saturday — the mock feed ticks 24/7
    assertThat(bucketer.bucketFor(ist("2026-06-13T11:07:42"))).isEqualTo(ist("2026-06-13T11:07:00"));
    // uncovered year never throws
    assertThat(bucketer.bucketFor(ist("2030-03-03T11:07:42"))).isEqualTo(ist("2030-03-03T11:07:00"));
  }

  @Test
  void clampDisabledFloorsPlainMinuteEvenInsideAndAfterSession() {
    // The mock CandleBuilder runs with clamping off so its 1m feed keeps advancing 24/7 (a clamped
    // feed freezes at 15:29 all evening — no new closed bars, starving the live signal engine).
    SessionBucketer unclamped = new SessionBucketer(MarketCalendar.nse(), false);
    // a trading-day post-close tick floors to its own minute, NOT the 15:29 session clamp
    assertThat(unclamped.bucketFor(ist("2026-06-10T15:45:10"))).isEqualTo(ist("2026-06-10T15:45:00"));
    // pre-open too — no 09:15 fold
    assertThat(unclamped.bucketFor(ist("2026-06-10T09:08:30"))).isEqualTo(ist("2026-06-10T09:08:00"));
  }
}

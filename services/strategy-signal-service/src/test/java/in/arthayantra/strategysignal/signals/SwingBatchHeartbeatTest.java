package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The swing-batch dead-man's-switch: pings the configured external monitor once per evening; the
 * monitor alerts when the ping is ABSENT. Verifies the arming gate, the ping target, the fail-soft
 * contract (a ping error must never surface to the batch) and — the point of the run-marker gate —
 * that the ping is EARNED: it is withheld when an armed family recorded no {@code swing_batch_runs}
 * marker for the session, while an NSE holiday, a disarmed family and a date past the bundled
 * calendar's coverage all still ping (a withheld ping there would be a false alarm).
 */
class SwingBatchHeartbeatTest {

  /** A weekday NSE trading day inside the bundled calendar's coverage. */
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 1, 27); // Tuesday

  /** Republic Day — a MONDAY NSE holiday, the shape a naive marker gate would false-alarm on. */
  private static final LocalDate WEEKDAY_HOLIDAY = LocalDate.of(2026, 1, 26);

  /** A weekday past the bundled holiday CSVs' covered years (2024-2026) — the CD-2 cliff. */
  private static final LocalDate PAST_COVERAGE = LocalDate.of(2027, 1, 4); // Monday

  private static final String PING_URL = "https://hc-ping.com/abc-123";

  /** Answers the marker probe from an in-memory set; optionally fails the read outright. */
  private static final class Markers extends SwingBatchRunRepository {
    private final Set<String> recorded = new HashSet<>();
    private final boolean fail;
    private int reads;

    Markers(boolean fail) {
      super(null, null);
      this.fail = fail;
    }

    Markers record(String batch, LocalDate session) {
      recorded.add(batch + "@" + session);
      return this;
    }

    @Override
    public boolean hasRun(String batch, LocalDate sessionDate) {
      reads++;
      if (fail) {
        throw new org.springframework.dao.DataAccessResourceFailureException("simulated DB outage");
      }
      return recorded.contains(batch + "@" + sessionDate);
    }
  }

  /** Captures the ping target instead of hitting the network (send() is package-private). */
  private static final class Recording extends SwingBatchHeartbeat {
    private final boolean fail;
    private String pinged;
    private int calls;

    Recording(String url, SwingBatchRunRepository runs, boolean fail) {
      this(url, runs, fail, Clock.systemUTC(), true, true);
    }

    Recording(
        String url,
        SwingBatchRunRepository runs,
        boolean fail,
        Clock clock,
        boolean minerviniArmed,
        boolean manasArmed) {
      super(url, runs, clock, minerviniArmed, manasArmed);
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

  private static Markers bothFamiliesRan() {
    return new Markers(false).record("minervini", TRADING_DAY).record("manas-arora", TRADING_DAY);
  }

  private static Recording armed(Markers markers) {
    return new Recording(PING_URL, markers, false);
  }

  // ---- the run-marker truth table --------------------------------------------------------------

  @Test
  void pingsWhenEveryArmedFamilyRecordedItsMarker() {
    Recording heartbeat = armed(bothFamiliesRan());

    heartbeat.pingIfRecorded(TRADING_DAY);

    assertThat(heartbeat.pinged).isEqualTo(PING_URL);
    assertThat(heartbeat.calls).isEqualTo(1);
  }

  @Test
  void withholdsThePingWhenAnArmedFamilyHasNoMarkerOnTheTradingDay() {
    // The gap this gate closes: the stack is UP (so the beat runs) but manas-arora's funnel read
    // failed, so SwingBatchRecorder wrote no marker even though its exit pass had already run.
    Recording heartbeat = armed(new Markers(false).record("minervini", TRADING_DAY));

    heartbeat.pingIfRecorded(TRADING_DAY);

    assertThat(heartbeat.calls).isZero(); // withheld — the external monitor alarms on the absence
  }

  @Test
  void pingsOnTheWeekdayNseHolidayEvenWithNoMarkerAtAll() {
    // No batch runs on a holiday, so no marker exists; withholding here would false-alarm on every
    // NSE holiday, and the cron is MON-FRI so weekday holidays DO reach this method.
    Markers markers = new Markers(false);
    Recording heartbeat = armed(markers);

    heartbeat.pingIfRecorded(WEEKDAY_HOLIDAY);

    assertThat(heartbeat.calls).isEqualTo(1);
    assertThat(markers.reads).isZero(); // decided by the calendar, never by the marker
  }

  @Test
  void pingsPastTheCalendarCoverageWithoutCheckingMarkers() {
    // MarketCalendar.isTradingDay throws outside the bundled years. Fail OPEN: degrade to the
    // pre-gate behaviour rather than false-alarm on every uncovered holiday — and never let the
    // throw escape the scheduled method.
    Markers markers = new Markers(false);
    Recording heartbeat = armed(markers);

    assertThatCode(() -> heartbeat.pingIfRecorded(PAST_COVERAGE)).doesNotThrowAnyException();

    assertThat(heartbeat.calls).isEqualTo(1);
    assertThat(markers.reads).isZero();
  }

  @Test
  void pingsWhenNoSwingFamilyIsArmed() {
    // Both flags off (the shipped default): SwingBatchRecorder short-circuits before the marker
    // write, so requiring a marker would withhold the ping every evening forever.
    Markers markers = new Markers(false);
    Recording heartbeat = new Recording(PING_URL, markers, false, Clock.systemUTC(), false, false);

    heartbeat.pingIfRecorded(TRADING_DAY);

    assertThat(heartbeat.calls).isEqualTo(1);
    assertThat(markers.reads).isZero();
  }

  @Test
  void disarmedFamilyDoesNotWithholdThePingForTheArmedOne() {
    Markers markers = new Markers(false).record("minervini", TRADING_DAY);
    Recording heartbeat = new Recording(PING_URL, markers, false, Clock.systemUTC(), true, false);

    heartbeat.pingIfRecorded(TRADING_DAY);

    assertThat(heartbeat.calls).isEqualTo(1); // manas-arora is off, so its absent marker is expected
    assertThat(markers.reads).isEqualTo(1); // only the armed family was probed
  }

  @Test
  void withholdsThePingWhenTheMarkerReadItselfFails() {
    // Fail CLOSED: the marker lives in the DB the batch writes to, so an unreadable marker at 20:15
    // is far more likely a real miss than a blip. It must still not throw out of @Scheduled.
    Recording heartbeat = armed(new Markers(true));

    assertThatCode(() -> heartbeat.pingIfRecorded(TRADING_DAY)).doesNotThrowAnyException();

    assertThat(heartbeat.calls).isZero();
  }

  // ---- the session date is IST, never UTC ------------------------------------------------------

  @Test
  void beatResolvesTheSessionDateInIstNotUtc() {
    // 2026-01-26T19:00Z is 2026-01-27 00:30 IST. The IST date (Tue 27th) is a TRADING day with no
    // marker -> withhold; the UTC date (Mon 26th) is Republic Day -> would ping. Only an IST-derived
    // session date can produce the withheld ping asserted here.
    Clock istNextDay = Clock.fixed(Instant.parse("2026-01-26T19:00:00Z"), ZoneOffset.UTC);
    Recording heartbeat =
        new Recording(PING_URL, new Markers(false), false, istNextDay, true, true);

    heartbeat.beat();

    assertThat(heartbeat.calls).isZero();
  }

  @Test
  void beatPingsThroughTheFullPathWhenTheSessionIsAccountedFor() {
    // 2026-01-27T14:45Z == 20:15 IST, the real cron instant.
    Clock atBeatTime = Clock.fixed(Instant.parse("2026-01-27T14:45:00Z"), ZoneOffset.UTC);
    Recording heartbeat =
        new Recording(PING_URL, bothFamiliesRan(), false, atBeatTime, true, true);

    heartbeat.beat();

    assertThat(heartbeat.pinged).isEqualTo(PING_URL);
    assertThat(heartbeat.calls).isEqualTo(1);
  }

  // ---- pre-existing arming / fail-soft contract ------------------------------------------------

  @Test
  void beatNoOpsOnBlankUrl() {
    Markers markers = new Markers(false);
    Recording heartbeat = new Recording("  ", markers, false);

    heartbeat.beat();

    assertThat(heartbeat.calls).isZero(); // no ping attempted when unarmed
    assertThat(markers.reads).isZero(); // and no DB read either
  }

  @Test
  void beatSwallowsSendFailure() {
    Recording heartbeat = new Recording(PING_URL, bothFamiliesRan(), true);
    // A network/monitor failure must never propagate to the batch — the external monitor's own
    // missed-ping alert is the backstop.
    assertThatCode(() -> heartbeat.pingIfRecorded(TRADING_DAY)).doesNotThrowAnyException();
    assertThat(heartbeat.calls).isEqualTo(1);
  }
}

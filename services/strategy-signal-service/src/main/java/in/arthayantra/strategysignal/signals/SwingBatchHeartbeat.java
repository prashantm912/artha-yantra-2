package in.arthayantra.strategysignal.signals;

import in.arthayantra.marketcalendar.MarketCalendar;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * External dead-man's-switch for the daily swing batches — the ONE failure the in-stack canaries
 * cannot catch: the whole stack (or host) being DOWN at batch time.
 *
 * <p>The P0-4 {@code strategy.swing_batch_runs} did-not-run canary and the market-data
 * {@code DataHealthCanary} both run INSIDE this stack, so a full-stack/host outage kills the watchman
 * together with the batch and no alert fires — the root cause of the 2026-07-09 silently-missed 20:05
 * batch (Docker was down; nothing on-box could report it). This heartbeat pings an EXTERNAL monitor
 * (healthchecks.io / UptimeRobot heartbeat / any dead-man's-switch URL) once per trading evening,
 * AFTER both swing batches (Minervini 20:00, Manas 20:05). If the stack is down at 20:15 IST the ping
 * never arrives and the external monitor alerts the owner on the missed schedule — off-box, so it
 * survives exactly the outage the in-stack canaries can't see.
 *
 * <p><b>The ping is EARNED, not unconditional.</b> Until this gate existed the beat fired whatever the
 * batches had done, so it proved only that a JVM was alive at 20:15 — a stack that was UP but whose
 * batch silently failed pinged green, and the miss surfaced ~12 h later at the 08:30
 * {@code SwingBatchCanary}. That shape is REAL, not hypothetical: when a family's funnel read fails over
 * HTTP the recorder's {@code snapshotAvailable} is false, so no {@code swing_batch_runs} marker is
 * written — while the exit pass has ALREADY run and can have closed paper positions. The beat now pings
 * only when every EXPECTED family recorded its marker for today's IST session; otherwise it stays silent
 * and the external monitor alarms on the missed schedule. Absence is the alarm — the same contract as
 * {@link SessionLivenessHeartbeat}, and it is what makes this fail CLOSED on a genuine miss.
 *
 * <p><b>Truth table</b>, evaluated for TODAY's IST date in {@link #batchesRecorded}, first match wins:
 *
 * <ol>
 *   <li>No swing family armed → <b>PING</b> — nothing is ever expected, on any date.
 *   <li>Date outside the bundled calendar's covered years (CD-2 cliff) → <b>WITHHOLD</b>.
 *   <li>Not an NSE trading day (holiday / weekend) → <b>PING</b> — no batch was expected.
 *   <li>Trading day, every armed family has its marker → <b>PING</b> — the batches ran.
 *   <li>Trading day, an armed family has NO marker → <b>WITHHOLD</b> — this is the whole point.
 *   <li>The marker read itself threw → <b>WITHHOLD</b> — the run cannot be proven.
 * </ol>
 *
 * <p><b>Holidays are why the calendar is load-bearing.</b> The cron is MON-FRI, but NSE holidays fall on
 * weekdays — a naive "no marker ⇒ withhold" would raise a FALSE alarm on every one of them, and a pager
 * the owner learns to ignore is worse than no pager. {@link MarketCalendar#nse()} decides whether a
 * session was expected at all.
 *
 * <p><b>Past the calendar's coverage the gate fails CLOSED</b> (the bundled holiday CSVs cover a FIXED
 * year set; {@link MarketCalendar#isTradingDay} throws outside it). Pinging there was the first design
 * and it is wrong: outside coverage a holiday is indistinguishable from a trading day, so a green ping
 * would report success through EVERY real miss for the whole uncovered window — precisely the silent lie
 * this gate exists to remove, merely relocated to a window nobody is watching. And the window is not
 * hypothetical: the 2027 NSE/BSE holiday lists are not published until roughly December 2026, while the
 * bundled CSVs stop at 2026 and {@code CalendarHorizonCanaryTest} reddens on 2026-11-17 — a known, dated,
 * multi-week gap. Withholding costs a burst of false alarms an operator can anticipate and silence from
 * the WARN this logs; fail-open costs a detector that lies exactly when it is least supervised. The throw
 * is still caught either way — letting it escape would kill the schedule outright. Arming is checked
 * FIRST (row 1), so an idle configuration cannot be paged by the cliff.
 *
 * <p><b>A disarmed family is expected to record nothing.</b> {@code SwingBatchRecorder} short-circuits a
 * flag-off family before the marker write, so requiring its marker would withhold the ping EVERY evening
 * for as long as the owner leaves it off — a permanent false alarm. Arming is read from the SAME two
 * properties the doctrines themselves bind, so the two cannot diverge; and unlike the next-morning
 * {@code SwingBatchCanary} — which must consult the V047 schedule-time intent ledger because the flag may
 * have moved since the session it judges — this beat runs 15 minutes after the schedulers read those very
 * properties in the same JVM, so the current value IS the schedule-time value.
 *
 * <p><b>IST.</b> The session date is computed in Java from the injected clock in {@link MarketCalendar#IST}
 * and compared against the {@code DATE run_date} column {@code SwingBatchRecorder} stamps the same way. No
 * SQL {@code now()}/{@code ::date} is involved on this path — that is UTC and would slice the session a day
 * early either side of IST midnight.
 *
 * <p>Dormant until armed: it loads only when {@code artha.heartbeat.url} is set (paste the monitor's
 * ping URL into {@code .env} as {@code ARTHA_HEARTBEAT_URL}, then redeploy). Configure the external
 * check to EXPECT a ping on the matching schedule (cron {@code 15 20 * * 1-5}, TZ Asia/Kolkata) with a
 * grace window, so a missed 20:15 ping raises the alert. Fail-soft: a ping failure is logged, never
 * thrown, and neither is a marker-read failure — the batch is unaffected (this observes it, never gates
 * it).
 *
 * <p><b>The ping URL never reaches the log, directly or via a throwable.</b> It is a secret-ish token from
 * {@code .env}, and both {@code URI.create}'s {@code IllegalArgumentException} and the HTTP client's
 * connect failures embed the FULL request URL in their message — so the ping catch logs the exception
 * CLASS only, never {@code e}/{@code e.getMessage()}/{@code e.toString()}. The marker-read catch is the
 * one place a message IS logged, and it is a JDBC exception that has never seen this URL.
 */
@Component
@ConditionalOnProperty(name = "artha.heartbeat.url")
public class SwingBatchHeartbeat {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchHeartbeat.class);

  /** The {@code swing_batch_runs.batch} keys — the same two {@code SwingBatchCanary#check} sweeps. */
  private static final String MINERVINI = "minervini";

  private static final String MANAS_ARORA = "manas-arora";

  private final String url;
  private final SwingBatchRunRepository runs;
  private final Clock clock;
  private final boolean minerviniArmed;
  private final boolean manasArmed;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /**
   * Wires the dead-man's-switch ping URL (present only when armed — see the class conditional), the
   * {@code swing_batch_runs} marker the ping is now earned against, the shared clock, and the two family
   * arming flags — deliberately the SAME property keys {@code MinerviniDoctrine} and
   * {@code ManasDoctrine} bind, so this gate's idea of "expected to run" cannot drift from theirs.
   */
  public SwingBatchHeartbeat(
      @Value("${artha.heartbeat.url}") String url,
      SwingBatchRunRepository runs,
      Clock clock,
      @Value("${artha.minervini.swing.enabled:false}") boolean minerviniArmed,
      @Value("${artha.manas-arora.swing.enabled:false}") boolean manasArmed) {
    this.url = url;
    this.runs = runs;
    this.clock = clock;
    this.minerviniArmed = minerviniArmed;
    this.manasArmed = manasArmed;
  }

  /** Post-batch daily ping (20:15 IST weekdays) — after the 20:00 + 20:05 swing batches. */
  @Scheduled(cron = "${artha.heartbeat.swing-cron:0 50 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void beat() {
    if (url == null || url.isBlank()) {
      return; // belt-and-braces; the conditional already gates loading
    }
    pingIfRecorded(LocalDate.now(clock.withZone(MarketCalendar.IST)));
  }

  /**
   * The decide-then-ping seam (package-private so a unit test drives it with an explicit session date —
   * no clock or network reads). Pings ONLY when the session's batches are accounted for; otherwise it
   * returns silently so the external monitor alarms on the withheld ping.
   */
  void pingIfRecorded(LocalDate session) {
    if (!batchesRecorded(session)) {
      return; // withhold the ping — absence is the alarm
    }
    try {
      send(url);
      log.info("swing batch heartbeat: pinged the external dead-man's-switch");
    } catch (Exception e) {
      // NEVER log the throwable's message or toString() here. The ping URL is a secret-ish token
      // from .env, and both URI.create's IllegalArgumentException and the HttpClient's connect
      // failures embed the FULL request URL in their message — logging `e` would publish the token
      // to the service log. The exception CLASS is the whole diagnostic budget on this path.
      log.warn(
          "swing batch heartbeat ping failed (external monitor may alert): {} (message withheld —"
              + " it can embed the secret ping URL)",
          e.getClass().getName());
    }
  }

  /**
   * Whether every family EXPECTED to run for {@code session} recorded its {@code swing_batch_runs}
   * marker — the class-level truth table, in order. Package-private + fully parameterized for unit
   * testing. Never throws: a {@code @Scheduled} method that dies takes the whole schedule with it.
   */
  boolean batchesRecorded(LocalDate session) {
    // Arming is checked BEFORE the calendar: with no family armed nothing is ever expected on any
    // date, so neither a holiday nor the coverage cliff can turn an idle configuration into a page.
    List<String> expected = expectedBatches();
    if (expected.isEmpty()) {
      log.info("swing batch heartbeat: no swing family is armed — nothing was expected, pinging");
      return true;
    }
    try {
      if (!calendar.isTradingDay(session)) {
        log.info(
            "swing batch heartbeat: {} is not an NSE trading day — no batch was expected, pinging",
            session);
        return true;
      }
    } catch (IllegalArgumentException uncoveredYear) {
      // Fail CLOSED. Outside coverage we cannot tell a holiday from a trading day, and pinging would
      // make EVERY real miss report green for the whole uncovered window — the exact lie this gate
      // exists to prevent, and not a hypothetical one: the 2027 NSE/BSE holiday lists are not
      // published until ~Dec 2026 while the horizon canary reddens 2026-11-17, so there is a known,
      // dated multi-week window with no coverage. A withheld ping is loud, anticipatable and
      // self-announcing (this WARN plus the monitor's alarm); a green lie is neither.
      log.error(
          "swing batch heartbeat: the NSE calendar does not cover {} — WITHHOLDING the ping because"
              + " a session cannot be judged expected-or-not (CD-2 calendar cliff; refresh the"
              + " bundled holiday CSVs to restore it)",
          session);
      return false;
    }
    try {
      for (String batch : expected) {
        if (!runs.hasRun(batch, session)) {
          log.error(
              "swing batch heartbeat: the {} swing batch recorded NO run marker for session {} —"
                  + " WITHHOLDING the external ping so the monitor alarms now rather than at the"
                  + " 08:30 canary",
              batch,
              session);
          return false;
        }
      }
      return true;
    } catch (RuntimeException e) {
      // Fail CLOSED. The marker lives in the database the batch writes to, so a read that fails at
      // 20:15 far more likely means the 20:00 batch could not write than that a blip straddles only
      // this beat. On an alerting path an unprovable state must be loud.
      //
      // The message IS kept here, unlike the ping path above: this exception comes from JDBC and can
      // never have seen the heartbeat URL, and the DB password is a separate Spring property (see
      // SecretFilePasswordPostProcessor), never embedded in the JDBC URL a connection failure would
      // quote. The SQL/driver message is the only diagnostic on this branch, so it is worth keeping.
      log.error(
          "swing batch heartbeat: the run-marker read for session {} failed — WITHHOLDING the ping"
              + " (cannot prove the batches ran): {}",
          session,
          e.toString());
      return false;
    }
  }

  /** The families whose marker is required for a trading session — a disarmed family records none. */
  private List<String> expectedBatches() {
    List<String> expected = new ArrayList<>(2);
    if (minerviniArmed) {
      expected.add(MINERVINI);
    }
    if (manasArmed) {
      expected.add(MANAS_ARORA);
    }
    return expected;
  }

  /** The actual GET — package-private so a unit test can capture it without real network I/O. */
  void send(String pingUrl) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(pingUrl)).timeout(Duration.ofSeconds(5)).GET().build();
    http.send(request, HttpResponse.BodyHandlers.discarding());
  }
}

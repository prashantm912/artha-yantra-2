package in.arthayantra.marketdata.kite.session.autologin;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.kite.session.KiteSessionService;
import in.arthayantra.marketdata.kite.session.KiteSessionStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Automates the daily Kite login: on a trading morning, if the session is not already CONNECTED,
 * run the browser leg once and hand the {@code request_token} to the EXISTING
 * {@link KiteSessionService#exchange}.
 *
 * <p><b>The manual browser flow remains fully intact and is still the fallback.</b> This class only
 * ever removes a wait; it never becomes the only way in. A completed manual login makes the
 * scheduled attempt a clean no-op — and, since review Major 5, makes a QUEUED delayed re-attempt a
 * no-op too.
 *
 * <p><b>⚠️ THE FAILURE DESIGN MATTERS MORE THAN THE HAPPY PATH.</b> The endpoints behind
 * {@link LoginWireClient} are undocumented and can change without notice, and a break lands in the
 * morning with the open at 09:15.
 *
 * <ul>
 *   <li><b>ONE attempt per trigger, and at most {@value #MAX_ATTEMPTS_PER_DAY} per TRADING DAY.</b>
 *       A wrong-password loop locks the BROKER ACCOUNT, which on a market morning is strictly worse
 *       than the ritual this replaces.
 *   <li><b>⚠️ Terminality is per DAY, not per firing (review Major 4).</b> The first cut tracked
 *       nothing across invocations: it alerted on a 4xx and returned, so a cron override firing
 *       more than once — {@code artha.kite.auto-login.cron} is configurable, and a multi-fire
 *       expression is a one-line {@code .env} change — would have resubmitted the rejected password
 *       and been granted a FRESH two-attempt chain every firing. The account-lock hazard the whole
 *       design exists to avoid, reachable by config, with every unit test green. The daily ledger
 *       below is claimed ATOMICALLY and gates every entry point, so the cap holds across the cron,
 *       the delayed re-attempt and any future caller.
 *   <li><b>⚠️ And the terminal flag is DURABLE (review round 3).</b> The in-memory ledger alone
 *       died with the process, so a restart handed the day a clean slate and a multi-fire cron
 *       could resubmit an already-rejected password. The flag now lives in
 *       {@code marketdata.canary_runs} keyed by IST date — no migration, and the PRIMARY KEY is the
 *       atomicity. The attempt COUNT stays in memory on purpose: replaying a transport failure
 *       cannot lock an account, so it is not worth a durable write. See
 *       {@link AutoLoginTerminalLedger}.
 *   <li><b>Everything else is terminal for the day</b>, alerted through {@link NtfyClient} with the
 *       failure CLASS only, synchronously rather than inferred later from a downstream symptom.
 *   <li><b>⚠️ Silence is also a failure.</b> A job that never ran looks identical, from outside, to
 *       one that succeeded. {@link #watchdog()} alerts if the session is not CONNECTED by its
 *       deadline REGARDLESS OF WHY — including "this bean's own cron never fired". It deliberately
 *       does NOT attempt a login: a second credential attempt is exactly what the cap forbids, and
 *       the watchdog's job is to fetch a human, not to try again.
 * </ul>
 *
 * <p><b>⚠️ Scheduling is bounded by the owner's operating window.</b> The machine is off 19:00–08:00
 * IST, so the often-quoted "log in at 07:30, before the 08:30 instrument sync" is not merely
 * suboptimal — a 07:30 job would never run at all, and {@code OperatingWindowTest} refuses it. The
 * defaults are therefore 08:05 (login) and 08:15 (watchdog), both after the machine is up and both
 * ahead of the 08:30 {@code InstrumentSyncScheduler} that needs a live token.
 *
 * <p>Runs on {@code monitorTaskScheduler} alongside {@code SessionHealthProbe} — the other bounded
 * synchronous-HTTP detector on that pool — so a hung batch job on the shared default pool cannot
 * starve the morning login.
 */
public class KiteAutoLoginService {

  private static final Logger log = LoggerFactory.getLogger(KiteAutoLoginService.class);

  /**
   * The hard per-trading-day ceiling on wire attempts: one scheduled attempt plus, at most, one
   * delayed transport-only re-attempt.
   *
   * <p>⚠️ This is a CAP, not the policy. The policy is {@link LoginRefusal#retryable()} — most
   * failures never reach a second attempt at all. The cap is what holds when the policy is
   * bypassed by a configuration nobody reviewed.
   */
  static final int MAX_ATTEMPTS_PER_DAY = 2;

  private static final String ATTEMPTS = "ay_kite_auto_login_attempts_total";
  private static final String SUCCESSES = "ay_kite_auto_login_success_total";
  private static final String REFUSALS = "ay_kite_auto_login_refused_total";
  private static final String SUPPRESSED = "ay_kite_auto_login_suppressed_total";
  private static final String WATCHDOG_ALERTS = "ay_kite_auto_login_watchdog_alerts_total";

  /** Why an entry point declined to reach the wire — a closed set, because it is a metric tag. */
  private enum Suppression {
    /** The session was already CONNECTED (a manual login, or an earlier success). */
    ALREADY_CONNECTED,
    /** A terminal refusal was already recorded for this trading day. */
    TERMINAL_FOR_DAY,
    /** The per-day attempt ceiling is spent. */
    DAILY_CAP,
    /**
     * The durable terminal-day record could not be READ. Blocking, deliberately — see
     * {@link AutoLoginTerminalLedger}. A distinct reason from {@link #TERMINAL_FOR_DAY} because the
     * two need different responses: one is the guard working, the other is the guard blind.
     *
     * <p>⚠️ Deliberately does NOT fire an ntfy alert. The 08:15 {@link #watchdog()} keys on
     * the OUTCOME (state != CONNECTED) rather than on any reason, so it already covers this: the
     * owner is told the session is not up, which is the actionable fact. Alerting here as well
     * would double-push on a database blip. If the watchdog is ever narrowed to specific causes,
     * this reason must gain its own alert.
     */
    LEDGER_UNAVAILABLE
  }

  /**
   * One trading day's attempt bookkeeping.
   *
   * <p>Held in a single {@link AtomicReference} and replaced wholesale, so "read the count, decide,
   * increment" is one atomic step. Two scheduler threads (the monitor pool has two) firing the cron
   * and a delayed re-attempt concurrently cannot both observe {@code attempts == 1}.
   */
  private record DayLedger(LocalDate day, int attempts, boolean terminal) {}

  private final LoginWireClient wireClient;
  private final AutoLoginTerminalLedger terminalLedger;
  private final KiteSessionService sessionService;
  private final KiteSessionStore store;
  private final NtfyClient ntfy;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final TaskScheduler taskScheduler;
  private final MeterRegistry meterRegistry;
  private final Duration retryDelay;
  private final AtomicReference<DayLedger> ledger = new AtomicReference<>();

  /** Wires the login leg, the existing exchange seam, alerting, the calendar and the clock. */
  public KiteAutoLoginService(
      LoginWireClient wireClient,
      AutoLoginTerminalLedger terminalLedger,
      KiteSessionService sessionService,
      KiteSessionStore store,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      TaskScheduler taskScheduler,
      MeterRegistry meterRegistry,
      Duration retryDelay) {
    this.wireClient = wireClient;
    this.terminalLedger = terminalLedger;
    this.sessionService = sessionService;
    this.store = store;
    this.ntfy = ntfy;
    this.calendar = calendar;
    this.clock = clock;
    this.taskScheduler = taskScheduler;
    this.meterRegistry = meterRegistry;
    this.retryDelay = retryDelay;
  }

  /**
   * Earliest and latest wall-clock IST at which a BOOT catch-up may reach the wire.
   *
   * <p>The window exists because the catch-up fires on every start, and most starts are not
   * mornings. Tonight (2026-08-27) market-data was recreated four times between 20:00 and 21:30
   * during a deploy and a test; without a window an armed service would have attempted a broker
   * login on each one. The lower bound matches the login cron's hour, the upper bound is the
   * close -- after it a session is not needed again until tomorrow, so touching the wire buys
   * nothing and spends the daily attempt cap.
   */
  private static final LocalTime CATCH_UP_FROM = LocalTime.of(8, 0);

  private static final LocalTime CATCH_UP_UNTIL = LocalTime.of(15, 30);

  /** Lets the context settle before a synchronous HTTP login runs. */
  private static final Duration CATCH_UP_DELAY = Duration.ofSeconds(20);

  /**
   * Boot catch-up: a cron fires at a wall-clock minute and NEVER backfills, so a machine that
   * comes up after the login cron gets no attempt AND no watchdog -- the alert misses too, because
   * it is armed on an event rather than on elapsed time. The first symptom would be the feed
   * failing at the 09:15 open.
   *
   * <p><b>This is the common case here, not an edge case.</b> The containers started 08:40:03 IST
   * on 2026-08-27 and 08:41 on 2026-08-26 -- both after 08:05, so on both of the last two trading
   * days an armed auto-login would have done nothing at all, silently.
   *
   * <p>⚠️ It goes through {@link #attemptIfStillNeeded} like every other entry point rather than
   * calling the wire directly. That is review Major 5 restated: a manual login completed while the
   * app was starting must suppress this, and only re-evaluating every precondition at the moment
   * of the attempt gets that right. It also inherits the durable terminal-day gate and the daily
   * attempt cap, so a restart loop cannot turn into a login loop.
   *
   * <p>⚠️ Scheduled onto {@code monitorTaskScheduler}, never run inline on the event thread: an
   * {@code @EventListener} is synchronous, so a blocking HTTP login here would delay startup and
   * the health check with it.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void catchUpOnBoot() {
    LocalTime nowIst = LocalTime.now(clock.withZone(Ist.ZONE));
    if (nowIst.isBefore(CATCH_UP_FROM) || nowIst.isAfter(CATCH_UP_UNTIL)) {
      log.info(
          "kite auto-login boot catch-up: {} IST is outside the {}-{} window — not attempting",
          nowIst, CATCH_UP_FROM, CATCH_UP_UNTIL);
      return;
    }
    log.info(
        "kite auto-login boot catch-up: started at {} IST, inside the window — will attempt in {}s"
            + " if the session is still not connected",
        nowIst, CATCH_UP_DELAY.toSeconds());
    taskScheduler.schedule(
        () -> attemptIfStillNeeded(true), clock.instant().plus(CATCH_UP_DELAY));
  }

  /**
   * The single daily login trigger (default 08:05 IST, weekdays).
   *
   * <p>⚠️ {@code cron} and {@code zone} stay on ONE source line: {@code CronPassthroughParityTest}
   * matches the {@code @Scheduled} site PER LINE and asserts the zone on that same slice, so
   * wrapping {@code zone} onto the next line reads to it as a job with no zone at all.
   */
  @Scheduled(
      cron = "${artha.kite.auto-login.cron:0 5 8 * * MON-FRI}", zone = "Asia/Kolkata",
      scheduler = "monitorTaskScheduler")
  public void scheduledLogin() {
    attemptIfStillNeeded(true);
  }

  /**
   * The silence detector (default 08:15 IST, weekdays) — alerts if the session is still not
   * CONNECTED, whatever the reason, while there is still time for the manual flow before the 08:30
   * instrument sync and the 09:15 open.
   *
   * <p>⚠️ Same one-line {@code cron}/{@code zone} rule as above.
   */
  @Scheduled(
      cron = "${artha.kite.auto-login.watchdog-cron:0 15 8 * * MON-FRI}", zone = "Asia/Kolkata",
      scheduler = "monitorTaskScheduler")
  public void watchdog() {
    if (!isTradingDaySafe(today())) {
      return;
    }
    if (store.state() == KiteSessionStore.State.CONNECTED) {
      return;
    }
    meterRegistry.counter(WATCHDOG_ALERTS).increment();
    alert(
        "Kite session is NOT connected (state=" + store.state() + "). The 08:30 instrument sync and"
            + " the live feed will fail — complete the manual login now.");
  }

  /**
   * The ONE entry point to the wire, shared by the cron and the delayed re-attempt.
   *
   * <p>⚠️ Every precondition is re-evaluated HERE rather than at the call site — that is review
   * Major 5. The delayed re-attempt used to call straight through, so a manual login completed
   * during the retry delay did not suppress the queued automated login, and the owner got a second
   * session established behind their back minutes after they had already logged in by hand.
   */
  private void attemptIfStillNeeded(boolean mayRetry) {
    LocalDate today = today();
    if (!isTradingDaySafe(today)) {
      return;
    }
    if (store.state() == KiteSessionStore.State.CONNECTED) {
      suppressed(Suppression.ALREADY_CONNECTED);
      log.info("kite auto-login: session already CONNECTED — standing down");
      return;
    }
    // ⚠️ The DURABLE gate, consulted BEFORE anything touches the network. In-memory state
    // alone was review Major 4's residue: it does not survive a restart, and the record it loses is
    // the one that prevents a rejected password being sent again.
    AutoLoginTerminalLedger.Verdict verdict = terminalLedger.verdictFor(today);
    if (verdict == AutoLoginTerminalLedger.Verdict.TERMINAL_RECORDED) {
      suppressed(Suppression.TERMINAL_FOR_DAY);
      log.warn(
          "kite auto-login: a terminal refusal is on record for {} — NOT re-submitting credentials;"
              + " the manual login is the path today",
          today);
      return;
    }
    if (verdict == AutoLoginTerminalLedger.Verdict.UNAVAILABLE) {
      suppressed(Suppression.LEDGER_UNAVAILABLE);
      return; // fail CLOSED: the ledger already logged why
    }
    if (!claimAttempt(today)) {
      return;
    }
    attempt(today, mayRetry);
  }

  /**
   * Atomically claims one attempt for {@code day}, or refuses and counts why.
   *
   * <p>The decision and the increment are a single {@code getAndUpdate}: the update function is
   * pure, so recomputing the verdict from the PREVIOUS value outside the CAS gives exactly the
   * answer the winning thread acted on.
   */
  private boolean claimAttempt(LocalDate day) {
    DayLedger previous =
        ledger.getAndUpdate(
            current -> {
              DayLedger today = forDay(current, day);
              return admits(today) ? new DayLedger(day, today.attempts() + 1, false) : today;
            });
    DayLedger today = forDay(previous, day);
    if (admits(today)) {
      return true;
    }
    if (today.terminal()) {
      suppressed(Suppression.TERMINAL_FOR_DAY);
      log.warn(
          "kite auto-login: a terminal refusal was already recorded for {} — NOT re-submitting"
              + " credentials; the manual login is the path today",
          day);
    } else {
      suppressed(Suppression.DAILY_CAP);
      log.warn(
          "kite auto-login: the {}-attempt ceiling for {} is spent — NOT re-submitting credentials",
          MAX_ATTEMPTS_PER_DAY, day);
    }
    return false;
  }

  /**
   * Blocks any further attempt today IN THIS PROCESS. Cannot fail, and is set on every terminal
   * outcome — including an exhausted transport chain, so the cap still bounds this JVM.
   */
  private void markTerminalInMemory(LocalDate day) {
    ledger.updateAndGet(current -> new DayLedger(day, forDay(current, day).attempts(), true));
  }

  /**
   * Blocks any further attempt today ACROSS RESTARTS — reserved for a VERDICT.
   *
   * <p>⚠️ <b>Gated on {@code !retryable()}, never on chain exhaustion, and that distinction is
   * the whole point of the durable/in-memory split.</b> An earlier cut wrote this row wherever the
   * retry chain ran out, so two consecutive NETWORK failures — cron fails, re-attempt fails,
   * {@code retryable() && mayRetry} is {@code true && false} — fell through and durably closed the
   * day. That is the exact opposite of {@link AutoLoginTerminalLedger}'s stated rationale, and on
   * this box it is the LIKELIEST morning failure rather than an edge case: the stack-outage
   * register records four Kite-connectivity outages on 2026-08-19/20 caused by this host's own
   * outbound network. A transient blip would have disarmed the feature for the day, clearable only
   * by a manual DELETE.
   *
   * <p>In-memory is always set first (it cannot fail); a failed durable write logs at ERROR rather
   * than propagating, so a bookkeeping failure never suppresses the owner's alert.
   */
  private void markTerminalDurably(LocalDate day) {
    terminalLedger.markTerminal(day);
  }

  /** A rolled-over ledger reads as a fresh day; the previous day's state never carries over. */
  private static DayLedger forDay(DayLedger current, LocalDate day) {
    return current == null || !current.day().equals(day)
        ? new DayLedger(day, 0, false)
        : current;
  }

  private static boolean admits(DayLedger today) {
    return !today.terminal() && today.attempts() < MAX_ATTEMPTS_PER_DAY;
  }

  /**
   * One login attempt, end to end. Only reachable through {@link #attemptIfStillNeeded}, so the
   * daily claim has already been made by the time this runs.
   *
   * <p>⚠️ The two calls sit in SEPARATE try blocks on purpose. They used to share one, so a
   * non-{@link LoginRefused} {@code RuntimeException} from the browser leg — {@code
   * LiveLoginWireClient.call} maps only three exception types, so others pass through — landed in
   * the exchange handler and told the owner "obtained a request_token but the exchange failed"
   * about a failure that never got a request_token. Pointing the owner at the wrong half of the
   * flow, on a market morning, costs more than the duplication here.
   */
  private void attempt(LocalDate day, boolean mayRetry) {
    meterRegistry.counter(ATTEMPTS).increment();
    String requestToken;
    try {
      requestToken = wireClient.fetchRequestToken();
    } catch (LoginRefused refused) {
      handleRefusal(day, refused, mayRetry);
      return;
    } catch (RuntimeException unclassified) {
      handleUnclassified(day, "the browser login leg", unclassified);
      return;
    }
    try {
      sessionService.exchange(requestToken);
    } catch (RuntimeException unclassified) {
      handleUnclassified(day, "the token exchange", unclassified);
      return;
    }
    meterRegistry.counter(SUCCESSES).increment();
    log.info("kite auto-login: session established");
  }

  /** A classified refusal: retry once if no verdict was reached, else close the day. */
  private void handleRefusal(LocalDate day, LoginRefused refused, boolean mayRetry) {
    meterRegistry.counter(REFUSALS, "reason", refused.refusal().name()).increment();
    if (refused.refusal().retryable() && mayRetry) {
      log.warn(
          "kite auto-login: {} at {} — ONE delayed re-attempt in {}",
          refused.refusal(), refused.step(), retryDelay);
      // Re-enters through attemptIfStillNeeded, so the trading-day check, the CONNECTED check
      // and the daily cap are all re-applied at the moment the re-attempt actually runs.
      taskScheduler.schedule(() -> attemptIfStillNeeded(false), clock.instant().plus(retryDelay));
      return;
    }
    markTerminalInMemory(day);
    if (refused.refusal().retryable()) {
      log.warn(
          "kite auto-login: the transport chain for {} is exhausted. NOT recording a durable"
              + " terminal day — no verdict was ever reached, so a restart may legitimately retry",
          day);
    } else {
      // A VERDICT: Zerodha looked at our credentials and said no. Resubmitting is the account-lock
      // vector, so this — and only this — is worth surviving a restart.
      markTerminalDurably(day);
    }
    log.error("kite auto-login FAILED, terminal for today: {}", refused.getMessage());
    alert(
        "Kite auto-login failed ("
            + refused.refusal()
            + " at "
            + refused.step()
            + "). No retry today — use the manual login before 08:30.");
  }

  /**
   * An exception this code does not model.
   *
   * <p>⚠️ NOT verdict-bearing, so it blocks this JVM but writes NO durable row. An
   * unclassified exception is by definition not Zerodha rejecting our credentials — the realistic
   * sources are a transient store/Redis failure inside {@code exchange}, or an exception type
   * {@code LiveLoginWireClient.call} does not map. Neither can lock the account, and durably
   * closing the day on one would be the same over-reach the transport case already taught.
   *
   * <p>⚠️ Class name only. A generic runtime message is not a controlled string and must
   * never be assumed free of submitted material.
   */
  private void handleUnclassified(LocalDate day, String where, RuntimeException unclassified) {
    markTerminalInMemory(day);
    meterRegistry.counter(REFUSALS, "reason", LoginRefusal.UNEXPECTED_RESPONSE.name()).increment();
    log.error(
        "kite auto-login FAILED in {} ({}), terminal for today in this process",
        where,
        unclassified.getClass().getSimpleName());
    alert(
        "Kite auto-login failed in "
            + where
            + " ("
            + unclassified.getClass().getSimpleName()
            + "). Use the manual login before 08:30.");
  }

  private void suppressed(Suppression reason) {
    meterRegistry.counter(SUPPRESSED, "reason", reason.name()).increment();
  }

  private void alert(String message) {
    ntfy.send("ArthaYantra Kite auto-login", "high", message);
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(Ist.ZONE));
  }

  private boolean isTradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException calendarCliff) {
      log.warn("kite auto-login: NSE calendar does not cover {} — standing down", day);
      return false;
    }
  }
}

package in.arthayantra.marketdata.kite.session.autologin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.kite.session.KiteSessionService;
import in.arthayantra.marketdata.kite.session.KiteSessionStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.RequiredSearch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;

/**
 * The orchestrator's FAILURE design, which matters more than its happy path.
 *
 * <p>The central assertion here is negative and it is the one whose absence would be dangerous: a
 * credential or TOTP refusal must produce EXACTLY ONE call to the wire client and schedule NOTHING.
 * A wrong-password loop locks the broker account, and no green happy-path test can detect one.
 *
 * <p>The {@link TaskScheduler} is mocked and the scheduled {@link Runnable} is CAPTURED, so "was a
 * re-attempt scheduled" is observable and, when one was, the test runs it by hand and counts what
 * follows — which is how the two-deep bound is proven rather than assumed.
 */
class KiteAutoLoginServiceTest {

  /**
   * A clock the test can move.
   *
   * <p>Needed because {@link KiteAutoLoginService} captures its {@link Clock} at construction: a
   * day-rollover test that builds a SECOND service passes trivially (a fresh instance has an empty
   * ledger by definition) and proves nothing about rollover at all. The ledger under test belongs
   * to ONE instance, so the clock has to be the thing that moves.
   */
  private static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return Ist.ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** A counting stub; the count is what makes "attempted exactly once" a real assertion. */
  private static final class CountingWireClient implements LoginWireClient {

    private final RuntimeException failure;
    private int calls;

    CountingWireClient(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public String fetchRequestToken() {
      calls++;
      if (failure != null) {
        throw failure;
      }
      return "tok-happy";
    }
  }

  private KiteSessionService sessionService;
  private KiteSessionStore store;
  private NtfyClient ntfy;
  private MeterRegistry meters;
  private TaskScheduler taskScheduler;
  private MutableClock clock;
  private MarketCalendar calendar;

  /**
   * The fake durable store, deliberately OUTSIDE any service instance.
   *
   * <p>This is what makes the restart simulation honest: a "restarted" service is a NEW
   * {@link KiteAutoLoginService} built against this SAME set, so its in-memory ledger is empty by
   * construction and the only thing that can still refuse is the persisted row. An earlier test in
   * this file was wrong in exactly the mirror direction — it built a fresh instance to prove day
   * ROLLOVER, which a fresh instance passes trivially — so the shape is called out rather than
   * trusted.
   */
  private Set<LocalDate> terminalRows;

  private boolean ledgerUnreadable;
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    sessionService = mock(KiteSessionService.class);
    store = mock(KiteSessionStore.class);
    ntfy = mock(NtfyClient.class);
    meters = new SimpleMeterRegistry();
    taskScheduler = mock(TaskScheduler.class);
    calendar = MarketCalendar.nse();
    clock = new MutableClock(morningOf(firstTradingDay()));
    when(store.state()).thenReturn(KiteSessionStore.State.TOKEN_EXPIRED);

    terminalRows = new HashSet<>();
    ledgerUnreadable = false;
    jdbc = mock(JdbcTemplate.class);
    // The real AutoLoginTerminalLedger runs against this; only the JDBC boundary is faked, so the
    // fail-closed branch and the count-based verdict are the production ones.
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any()))
        .thenAnswer(
            call -> {
              if (ledgerUnreadable) {
                throw new DataAccessResourceFailureException("connection refused (simulated)");
              }
              return terminalRows.contains((LocalDate) call.getArgument(3)) ? 1 : 0;
            });
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              terminalRows.add((LocalDate) call.getArgument(2));
              return 1;
            });
  }

  /**
   * A day the bundled NSE calendar agrees is a trading day.
   *
   * <p>Derived, not hardcoded: {@code MarketCalendar} is a final class (unmockable) and its bundled
   * holiday set moves between releases, so a literal date would eventually fail for a reason having
   * nothing to do with this class.
   */
  private LocalDate firstTradingDay() {
    LocalDate day = LocalDate.of(2026, 8, 3);
    for (int i = 0; i < 30; i++, day = day.plusDays(1)) {
      if (calendar.isTradingDay(day)) {
        return day;
      }
    }
    throw new IllegalStateException("no trading day in range — the bundled calendar is not usable");
  }

  private static Instant morningOf(LocalDate day) {
    return LocalDateTime.of(day, LocalTime.of(8, 5)).atZone(Ist.ZONE).toInstant();
  }

  private KiteAutoLoginService service(LoginWireClient wireClient) {
    return new KiteAutoLoginService(
        wireClient,
        new AutoLoginTerminalLedger(jdbc, clock),
        sessionService,
        store,
        ntfy,
        calendar,
        clock,
        taskScheduler,
        meters,
        Duration.ofMinutes(2));
  }

  /**
   * Count of a counter that may never have been registered. {@link #counter} uses the REQUIRED
   * search, which throws {@code MeterNotFoundException} when a counter never fired -- turning a
   * clean "expected 1 but was 0" into an exception whose stack trace buries the actual claim.
   */
  private double countOrZero(String name) {
    var found = meters.find(name).counter();
    return found == null ? 0d : found.count();
  }

  private double counter(String name, String... tags) {
    RequiredSearch search = meters.get(name);
    if (tags.length > 0) {
      search = search.tags(tags);
    }
    return search.counter().count();
  }

  /** Asserts nothing at all was handed to the scheduler — the no-retry-loop guarantee. */
  private void assertNothingWasScheduled() {
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
  }

  /** The single captured re-attempt, run once by hand. */
  private void runTheScheduledReattempt() {
    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler, times(1)).schedule(captor.capture(), any(Instant.class));
    captor.getValue().run();
  }

  @Test
  @DisplayName("a successful login hands the request_token to the EXISTING exchange seam")
  void aSuccessfulLoginExchangesTheToken() {
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).scheduledLogin();

    verify(sessionService).exchange("tok-happy");
    assertThat(wireClient.calls).isOne();
    assertThat(counter("ay_kite_auto_login_attempts_total")).isEqualTo(1.0);
    assertThat(counter("ay_kite_auto_login_success_total")).isEqualTo(1.0);
    assertNothingWasScheduled();
    verify(ntfy, never()).send(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("an already-CONNECTED session makes the trigger a clean no-op")
  void anAlreadyConnectedSessionIsANoOp() {
    // This is what keeps the MANUAL browser flow a real fallback: an owner who logs in by hand at
    // 08:02 must not have a second, automated login fired at them three minutes later.
    when(store.state()).thenReturn(KiteSessionStore.State.CONNECTED);
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).scheduledLogin();

    assertThat(wireClient.calls).isZero();
    verify(sessionService, never()).exchange(anyString());
    assertThat(meters.find("ay_kite_auto_login_attempts_total").counter()).isNull();
  }

  @Test
  @DisplayName("nothing is attempted on a non-trading day")
  void aNonTradingDayIsSkipped() {
    LocalDate day = firstTradingDay();
    while (calendar.isTradingDay(day)) {
      day = day.plusDays(1);
    }
    clock.now = morningOf(day);
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).scheduledLogin();
    service(wireClient).watchdog();

    assertThat(wireClient.calls).isZero();
    verify(ntfy, never()).send(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("a credential refusal attempts ONCE, schedules NOTHING and alerts")
  void aCredentialRefusalNeverRetries() {
    // ⚠️ THE assertion of this class. A second attempt with the same rejected password is how a
    // BROKER ACCOUNT gets locked on a market morning — strictly worse than the manual ritual this
    // feature replaces. Asserted two ways that can each fail alone: the wire client's own call
    // count, and the scheduler never being handed anything at all.
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(
                LoginWireClient.Step.CREDENTIALS, LoginRefusal.CREDENTIAL_REJECTED, "HTTP 403"));

    service(wireClient).scheduledLogin();

    assertThat(wireClient.calls).isOne();
    assertNothingWasScheduled();
    assertThat(counter("ay_kite_auto_login_attempts_total")).isEqualTo(1.0);
    assertThat(counter("ay_kite_auto_login_refused_total", "reason", "CREDENTIAL_REJECTED"))
        .isEqualTo(1.0);
    verify(ntfy).send(eq("ArthaYantra Kite auto-login"), eq("high"), contains("CREDENTIAL_REJECTED"));
  }

  @Test
  @DisplayName("a TOTP refusal attempts ONCE and never re-sends a code")
  void aTotpRefusalNeverRetries() {
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(LoginWireClient.Step.TWOFA, LoginRefusal.TOTP_REJECTED, "HTTP 400"));

    service(wireClient).scheduledLogin();

    assertThat(wireClient.calls).isOne();
    assertNothingWasScheduled();
    assertThat(counter("ay_kite_auto_login_refused_total", "reason", "TOTP_REJECTED")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("an unexpected response shape is terminal — the 'Zerodha changed something' case")
  void anUnexpectedShapeIsTerminal() {
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(
                LoginWireClient.Step.AUTHORIZE, LoginRefusal.UNEXPECTED_RESPONSE, "no token"));

    service(wireClient).scheduledLogin();

    assertThat(wireClient.calls).isOne();
    assertNothingWasScheduled();
    verify(ntfy).send(anyString(), anyString(), contains("UNEXPECTED_RESPONSE"));
  }

  @Test
  @DisplayName("a transport failure gets EXACTLY ONE delayed re-attempt, then goes terminal")
  void aTransportFailureGetsOneReattemptAndNoMore() {
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(LoginWireClient.Step.CREDENTIALS, LoginRefusal.NETWORK, "transport"));

    service(wireClient).scheduledLogin();

    assertThat(wireClient.calls).isOne();
    // No alert yet: the re-attempt may still succeed, and an alert per transport blip is how an
    // ops channel stops being read.
    verify(ntfy, never()).send(anyString(), anyString(), anyString());

    runTheScheduledReattempt();

    assertThat(wireClient.calls).isEqualTo(2);
    // ⚠️ The bound: the re-attempt is passed mayRetry=false, so a SECOND schedule() would show up
    // here as a third interaction and fail this verification.
    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    assertThat(counter("ay_kite_auto_login_attempts_total")).isEqualTo(2.0);
    verify(ntfy).send(anyString(), anyString(), contains("NETWORK"));
  }

  @Test
  @DisplayName("a failure AFTER the token is obtained alerts with the class name, never a message")
  void anExchangeFailureAlertsWithoutEchoingItsMessage() {
    // The message of an arbitrary downstream RuntimeException is not a controlled string, so it
    // must never reach a log line or a push.
    when(sessionService.exchange(anyString()))
        .thenThrow(new IllegalStateException("token=PLACEHOLDER-SENSITIVE-VALUE rejected"));
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).scheduledLogin();

    verify(ntfy).send(anyString(), anyString(), contains("IllegalStateException"));
    verify(ntfy, never()).send(anyString(), anyString(), contains("PLACEHOLDER-SENSITIVE-VALUE"));
    assertNothingWasScheduled();
  }

  @Test
  @DisplayName("the watchdog alerts on silence — and never attempts a second login")
  void theWatchdogAlertsOnSilenceWithoutLoggingIn() {
    // A job that never fired looks identical, from outside, to one that succeeded. The watchdog
    // keys on the OUTCOME (state != CONNECTED), not on whether the login ran, so it fires for
    // "the cron never ran" just as it does for "the login failed". It must NOT log in: a second
    // credential attempt is exactly what the no-retry rule forbids.
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).watchdog();

    assertThat(wireClient.calls).isZero();
    assertThat(counter("ay_kite_auto_login_watchdog_alerts_total")).isEqualTo(1.0);
    verify(ntfy).send(anyString(), anyString(), contains("TOKEN_EXPIRED"));
  }

  @Test
  @DisplayName("⚠️ a SECOND cron firing after a 4xx does NOT reach the wire — terminal is per DAY")
  void aSecondFiringAfterATerminalRefusalNeverResubmits() {
    // ⚠️ Cross-vendor review Major 4. The first cut tracked nothing across invocations: it alerted
    // on a 4xx and returned. `artha.kite.auto-login.cron` is a configurable, .env-overridable
    // property, so a multi-fire expression — `0 5,10,15 8 * * MON-FRI` is a one-line change — would
    // have resubmitted the rejected password on every firing AND granted a fresh two-attempt chain
    // each time. That is the account-lock hazard the whole design exists to prevent, reachable by
    // configuration, with every other test in this file green.
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(
                LoginWireClient.Step.CREDENTIALS, LoginRefusal.CREDENTIAL_REJECTED, "HTTP 403"));
    KiteAutoLoginService service = service(wireClient);

    service.scheduledLogin();
    assertThat(wireClient.calls).isOne();

    service.scheduledLogin();
    service.scheduledLogin();

    assertThat(wireClient.calls)
        .as("a terminal refusal is terminal for the DAY, not for the firing")
        .isOne();
    assertThat(counter("ay_kite_auto_login_suppressed_total", "reason", "TERMINAL_FOR_DAY"))
        .isEqualTo(2.0);
    assertNothingWasScheduled();
  }

  @Test
  @DisplayName("the daily ceiling caps even repeated TRANSPORT failures")
  void theDailyCeilingCapsTransportFailuresToo() {
    // A transport failure never records a DURABLE terminal day (no verdict was reached), so within
    // one process the attempt ceiling is what stands between a multi-fire cron and an unbounded
    // loop. ⚠️ This comment used to read "never marks the day terminal", full stop, while the
    // assertion twenty lines below said the opposite and explained why — a comment contradicting
    // the code it annotates, in the same method. Round-3 review caught it; the fix that made the
    // sentence true is the verdict-gated durable write, not an edit to the words.
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(LoginWireClient.Step.CREDENTIALS, LoginRefusal.NETWORK, "transport"));
    KiteAutoLoginService service = service(wireClient);

    service.scheduledLogin();
    runTheScheduledReattempt();
    assertThat(wireClient.calls).isEqualTo(KiteAutoLoginService.MAX_ATTEMPTS_PER_DAY);

    service.scheduledLogin();
    service.scheduledLogin();

    assertThat(wireClient.calls)
        .as("the per-day ceiling holds across every scheduler entry, not just within one chain")
        .isEqualTo(KiteAutoLoginService.MAX_ATTEMPTS_PER_DAY);
    // ⚠️ TERMINAL_FOR_DAY, not DAILY_CAP — and this test asserted the wrong one until it was run.
    // The second attempt carries mayRetry=false, so even a RETRYABLE refusal falls through to the
    // terminal branch and marks the day IN MEMORY. The cap still bounds the chain; the in-process
    // terminal flag is simply what the later firings trip over first. What it does NOT do is write
    // the durable row — aDoubleTransportFailureDoesNotDurablyCloseTheDay pins that separately.
    assertThat(counter("ay_kite_auto_login_suppressed_total", "reason", "TERMINAL_FOR_DAY"))
        .isEqualTo(2.0);
  }

  @Test
  @DisplayName("the DAILY_CAP arm fires when two firings race ahead of the queued re-attempt")
  void theDailyCapSuppressesAThirdFiringBeforeAnyTerminalMark() {
    // The reachability case for DAILY_CAP, and the sharpest form of the multi-fire hazard: two cron
    // firings both transport-fail and both queue a re-attempt BEFORE either chain has marked the
    // day terminal. Attempts are now spent with no terminal flag set, so the ceiling is the only
    // thing left holding the line — which is exactly why it exists as well as the terminal flag.
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(LoginWireClient.Step.CREDENTIALS, LoginRefusal.NETWORK, "transport"));
    KiteAutoLoginService service = service(wireClient);

    service.scheduledLogin();
    service.scheduledLogin();
    assertThat(wireClient.calls).isEqualTo(KiteAutoLoginService.MAX_ATTEMPTS_PER_DAY);

    service.scheduledLogin();

    assertThat(wireClient.calls).isEqualTo(KiteAutoLoginService.MAX_ATTEMPTS_PER_DAY);
    assertThat(counter("ay_kite_auto_login_suppressed_total", "reason", "DAILY_CAP")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("the ledger rolls over: a new trading day gets a fresh budget on the SAME instance")
  void aNewTradingDayGetsAFreshBudget() {
    // The cap must not become a permanent lockout — a 4xx on Monday cannot mute Tuesday. Driven on
    // ONE service instance with the clock moved under it, because a second instance would start
    // with an empty ledger and pass no matter what the rollover logic did.
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(
                LoginWireClient.Step.CREDENTIALS, LoginRefusal.CREDENTIAL_REJECTED, "HTTP 403"));
    KiteAutoLoginService service = service(wireClient);

    service.scheduledLogin();
    service.scheduledLogin();
    assertThat(wireClient.calls).as("terminal for today").isOne();

    LocalDate next = firstTradingDay().plusDays(1);
    while (!calendar.isTradingDay(next)) {
      next = next.plusDays(1);
    }
    clock.now = morningOf(next);

    service.scheduledLogin();

    assertThat(wireClient.calls)
        .as("a new trading day must be allowed to try again on the same instance")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("⚠️ a manual login during the retry delay suppresses the queued re-attempt")
  void aManualLoginDuringTheDelaySuppressesTheReattempt() {
    // ⚠️ Cross-vendor review Major 5. The delayed re-attempt used to call straight through without
    // rechecking state, so an owner who logged in by hand during the delay got a second session
    // established behind their back minutes later.
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(LoginWireClient.Step.CREDENTIALS, LoginRefusal.NETWORK, "transport"));
    service(wireClient).scheduledLogin();
    assertThat(wireClient.calls).isOne();

    when(store.state()).thenReturn(KiteSessionStore.State.CONNECTED);
    runTheScheduledReattempt();

    assertThat(wireClient.calls)
        .as("the manual login already succeeded — the queued automated login must stand down")
        .isOne();
    verify(sessionService, never()).exchange(anyString());
    assertThat(counter("ay_kite_auto_login_suppressed_total", "reason", "ALREADY_CONNECTED"))
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("⚠️ a RESTART after a 4xx still refuses — the terminal flag is durable")
  void aRestartedServiceStillRefusesAfterATerminalRefusal() {
    // ⚠️ Review round 3. The in-memory ledger died with the process, so a restart handed the day a
    // clean slate and the next firing resubmitted an already-rejected password — the account-lock
    // vector, surviving every other guard in this file.
    //
    // ⚠️ THE SIMULATION IS THE POINT AND IT IS EASY TO FAKE. `restarted` is a NEW service over the
    // SAME terminalRows set, so its in-memory ledger is empty by construction: the ONLY thing that
    // can refuse here is the persisted row. (This file already contains the mirror mistake made in
    // the other direction — a rollover test that built a fresh instance and therefore proved
    // nothing — so the shape is stated rather than assumed.)
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(
                LoginWireClient.Step.CREDENTIALS, LoginRefusal.CREDENTIAL_REJECTED, "HTTP 403"));

    service(wireClient).scheduledLogin();
    assertThat(wireClient.calls).isOne();
    assertThat(terminalRows)
        .as("the terminal refusal must have been PERSISTED, not merely remembered")
        .containsExactly(firstTradingDay());

    KiteAutoLoginService restarted = service(wireClient);
    restarted.scheduledLogin();
    restarted.scheduledLogin();

    assertThat(wireClient.calls)
        .as("a restarted process must not resubmit a password Zerodha already rejected today")
        .isOne();
    assertThat(counter("ay_kite_auto_login_suppressed_total", "reason", "TERMINAL_FOR_DAY"))
        .isEqualTo(2.0);
  }

  @Test
  @DisplayName("⚠️ an unreadable ledger FAILS CLOSED — it never falls through to the wire")
  void anUnreadableLedgerRefusesRatherThanGuessingTheDayIsOpen() {
    // Falling through on a read error would invert the whole guard: the one moment the database is
    // unreachable is not the moment to start assuming yesterday's rejection did not happen.
    ledgerUnreadable = true;
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).scheduledLogin();

    assertThat(wireClient.calls).isZero();
    verify(sessionService, never()).exchange(anyString());
    assertThat(counter("ay_kite_auto_login_suppressed_total", "reason", "LEDGER_UNAVAILABLE"))
        .isEqualTo(1.0);
    assertNothingWasScheduled();
  }

  @Test
  @DisplayName("a durable terminal row from an EARLIER day does not mute today")
  void aTerminalRowForAnotherDayDoesNotBlockToday() {
    // The durable gate is keyed by IST date; if it were keyed by anything coarser, one bad Monday
    // would silently disable the login forever and the symptom would be indistinguishable from the
    // feature simply not working.
    terminalRows.add(firstTradingDay().minusDays(7));
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).scheduledLogin();

    assertThat(wireClient.calls).isOne();
    verify(sessionService).exchange("tok-happy");
  }

  @Test
  @DisplayName("⚠️ a double TRANSPORT failure does NOT durably close the day")
  void aDoubleTransportFailureDoesNotDurablyCloseTheDay() {
    // ⚠️ Round-3 review Major 1. The durable write used to be gated on the retry chain being
    // EXHAUSTED rather than on a verdict, so: cron fails NETWORK -> re-attempt queued -> re-attempt
    // fails NETWORK with mayRetry=false -> `retryable() && mayRetry` is `true && false` -> fell
    // through to the durable write. Two transient blips closed the day across restarts, clearable
    // only by a manual DELETE — and on this host that is the LIKELIEST morning failure, not an
    // edge case (four Kite-connectivity outages on 2026-08-19/20, all the host's own outbound
    // network). It also directly contradicted AutoLoginTerminalLedger's own stated rationale.
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(LoginWireClient.Step.CREDENTIALS, LoginRefusal.NETWORK, "transport"));

    service(wireClient).scheduledLogin();
    runTheScheduledReattempt();

    assertThat(wireClient.calls).isEqualTo(KiteAutoLoginService.MAX_ATTEMPTS_PER_DAY);
    assertThat(terminalRows)
        .as("no verdict was ever reached, so nothing may survive a restart")
        .isEmpty();
  }

  @Test
  @DisplayName("an unclassified exception does not durably close the day either")
  void anUnclassifiedFailureDoesNotDurablyCloseTheDay() {
    // Not verdict-bearing: a transient store/Redis failure inside exchange() is not Zerodha
    // rejecting our credentials, so it blocks this JVM but must not survive a restart.
    when(sessionService.exchange(anyString()))
        .thenThrow(new IllegalStateException("redis unavailable (simulated)"));

    service(new CountingWireClient(null)).scheduledLogin();

    assertThat(terminalRows).isEmpty();
  }

  @Test
  @DisplayName("a CREDENTIAL refusal is the one that DOES durably close the day")
  void aCredentialRefusalIsWhatEarnsTheDurableRow() {
    // The positive half of the same rule — without this, deleting the durable write entirely would
    // still leave the two tests above green.
    CountingWireClient wireClient =
        new CountingWireClient(
            new LoginRefused(
                LoginWireClient.Step.CREDENTIALS, LoginRefusal.CREDENTIAL_REJECTED, "HTTP 403"));

    service(wireClient).scheduledLogin();

    assertThat(terminalRows).containsExactly(firstTradingDay());
  }

  @Test
  @DisplayName("⚠️ a pre-existing durable row for TODAY blocks the wire outright")
  void aPersistedTerminalRowForTodayBlocksTheWire() {
    // ⚠️ The ISOLATED positive test for the durable gate (round-3 review Minor 5). Every other
    // test that exercises it first has to CREATE the row through a refusal, so the gate and the
    // writer are proven together and a broken gate could hide behind a working writer. Here the row
    // is seeded directly and no refusal is involved at all: the wire client would SUCCEED if it
    // were ever called.
    terminalRows.add(firstTradingDay());
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).scheduledLogin();

    assertThat(wireClient.calls)
        .as("a day already recorded terminal must never reach the network")
        .isZero();
    verify(sessionService, never()).exchange(anyString());
    assertThat(counter("ay_kite_auto_login_suppressed_total", "reason", "TERMINAL_FOR_DAY"))
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("the watchdog is silent once the session is CONNECTED, however it got there")
  void theWatchdogIsSilentWhenConnected() {
    when(store.state()).thenReturn(KiteSessionStore.State.CONNECTED);

    service(new CountingWireClient(null)).watchdog();

    verify(ntfy, never()).send(anyString(), anyString(), anyString());
    assertThat(countOrZero("ay_kite_auto_login_watchdog_alerts_total")).isZero();
  }

  /**
   * The reason this catch-up exists: a cron fires at a wall-clock minute and never backfills, so a
   * machine up at 08:35 gets no login AND no watchdog. Measured: the containers started 08:40:03
   * IST on 2026-08-27 and 08:41 on 2026-08-26, so on both of the last two trading days an armed
   * auto-login would have done nothing at all, silently.
   */
  @Test
  void aLateBootInsideTheWindowAttemptsTheLoginTheCronMissed() {
    LocalDate day = firstTradingDay();
    clock.now = atIst(day, 8, 35);
    when(store.state()).thenReturn(KiteSessionStore.State.DISCONNECTED);
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).catchUpOnBoot();

    // Deferred onto the scheduler, never run inline: an @EventListener is synchronous, and a
    // blocking HTTP login on the boot thread would delay startup and the health check with it.
    assertThat(wireClient.calls).isZero();
    runTheBootLogin();
    assertThat(wireClient.calls).isOne();
  }

  /**
   * The owner's actual requirement, and the half a naive catch-up gets wrong: it must check
   * whether a login already happened rather than logging in again on every start.
   */
  @Test
  void aLateBootStandsDownWhenTheSessionIsAlreadyConnected() {
    LocalDate day = firstTradingDay();
    clock.now = atIst(day, 8, 35);
    when(store.state()).thenReturn(KiteSessionStore.State.CONNECTED);
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).catchUpOnBoot();
    runTheBootLogin();

    assertThat(wireClient.calls).isZero();
  }

  /**
   * ⚠️ Without a window the catch-up fires on EVERY start, and most starts are not mornings.
   * market-data was recreated four times between 20:00 and 21:30 on 2026-08-27 during a deploy and
   * a test; an armed service would have attempted a broker login on each one.
   */
  @Test
  void anEveningBootDoesNotReachTheWireAtAll() {
    LocalDate day = firstTradingDay();
    clock.now = atIst(day, 21, 25);
    when(store.state()).thenReturn(KiteSessionStore.State.DISCONNECTED);
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).catchUpOnBoot();

    assertNothingWasScheduled();
    assertThat(wireClient.calls).isZero();
  }

  /** The upper bound is the close: after it a session is not needed again until tomorrow. */
  @Test
  void aBootJustAfterTheCloseIsOutsideTheWindow() {
    LocalDate day = firstTradingDay();
    clock.now = atIst(day, 15, 31);
    when(store.state()).thenReturn(KiteSessionStore.State.DISCONNECTED);
    CountingWireClient wireClient = new CountingWireClient(null);

    service(wireClient).catchUpOnBoot();

    assertNothingWasScheduled();
    assertThat(wireClient.calls).isZero();
  }

  /**
   * A boot schedules TWO tasks: the login attempt, then the watchdog after it has settled. These
   * name which one they run, because a positional {@code times(1)} silently breaks the moment a
   * second task is added -- which is exactly how the watchdog half was caught.
   */
  private void runTheBootLogin() {
    captureBootTasks().get(0).run();
  }

  private void runTheBootWatchdog() {
    captureBootTasks().get(1).run();
  }

  private java.util.List<Runnable> captureBootTasks() {
    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler, times(2)).schedule(captor.capture(), any(Instant.class));
    return captor.getAllValues();
  }

  private static Instant atIst(LocalDate day, int hour, int minute) {
    return LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(Ist.ZONE).toInstant();
  }

  /**
   * ⚠️ The half that was nearly left open. Suppression paths deliberately do NOT alert -- the
   * LEDGER_UNAVAILABLE javadoc says so outright, on the grounds that "the 08:15 watchdog keys on
   * the OUTCOME". That reasoning holds only while the watchdog RUNS, and on a late boot it never
   * does. Without this the owner gets no login AND no alert.
   */
  @Test
  void aLateBootAlsoRunsTheWatchdogTheCronMissed() {
    clock.now = atIst(firstTradingDay(), 8, 35);
    when(store.state()).thenReturn(KiteSessionStore.State.DISCONNECTED);

    service(new CountingWireClient(null)).catchUpOnBoot();
    runTheBootWatchdog();

    assertThat(countOrZero("ay_kite_auto_login_watchdog_alerts_total")).isOne();
  }

  /** A connected session is the normal case and must stay silent. */
  @Test
  void theBootWatchdogStaysSilentWhenTheSessionIsConnected() {
    clock.now = atIst(firstTradingDay(), 8, 35);
    when(store.state()).thenReturn(KiteSessionStore.State.CONNECTED);

    service(new CountingWireClient(null)).catchUpOnBoot();
    runTheBootWatchdog();

    // find(), not get(): a counter that never fired was never REGISTERED, so the required-search
    // form throws MeterNotFoundException instead of reporting zero.
    assertThat(countOrZero("ay_kite_auto_login_watchdog_alerts_total")).isZero();
  }

  /**
   * Several restarts on one bad morning must not page several times -- that trains the owner to
   * ignore the channel, which is worse than not alerting.
   */
  @Test
  void repeatedBootsOnTheSameDayPageOnlyOnce() {
    clock.now = atIst(firstTradingDay(), 8, 35);
    when(store.state()).thenReturn(KiteSessionStore.State.DISCONNECTED);
    KiteAutoLoginService svc = service(new CountingWireClient(null));

    svc.watchdog();
    svc.watchdog();
    svc.watchdog();

    assertThat(countOrZero("ay_kite_auto_login_watchdog_alerts_total")).isOne();
  }
}

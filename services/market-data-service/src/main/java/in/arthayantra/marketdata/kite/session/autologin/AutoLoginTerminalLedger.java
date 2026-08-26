package in.arthayantra.marketdata.kite.session.autologin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The DURABLE record of "credentials were rejected today, do not send them again".
 *
 * <p><b>⚠️ Why this is persisted when the attempt COUNT is not.</b> The two things the orchestrator
 * tracks have different hazards, and only one of them can hurt the account:
 *
 * <ul>
 *   <li>A <b>terminal 4xx</b> means Zerodha looked at the user id, password or TOTP and said no.
 *       Resubmitting that is what LOCKS A BROKER ACCOUNT. It must survive a restart.
 *   <li>The <b>transport-retry count</b> (NETWORK / 5xx) means no verdict was reached at all.
 *       Replaying it cannot lock anything, so losing it on a restart costs at most one extra
 *       harmless attempt — it stays in memory, where it is cheap and race-free.
 * </ul>
 *
 * <p>Persisting only the flag is the whole design: it removes the account-lock vector without
 * adding a write to the hot path or a migration to this change.
 *
 * <p><b>No schema change.</b> This reuses {@code marketdata.canary_runs}, already the house per-day
 * claim idiom ({@code IngestCoverageCanary}, {@code EveningChainCanary}).
 *
 * <p><b>⚠️ WHAT THE PRIMARY KEY DOES AND DOES NOT BUY — read this before deleting anything.</b>
 * An earlier version of this javadoc claimed "the PRIMARY KEY IS the atomicity … two scheduler
 * threads racing on the same IST date cannot both conclude the day is still open". <b>That was
 * false, and it was false in a dangerous direction.</b> The gate is {@link #verdictFor}, a SELECT
 * issued as a separate statement minutes before any INSERT: two threads that both read
 * {@code count = 0} both get {@link Verdict#PROCEED}. {@code ON CONFLICT DO NOTHING} makes the
 * WRITE idempotent; it does not exclude the READ.
 *
 * <p>The three properties, stated separately so none is mistaken for another:
 *
 * <ul>
 *   <li><b>Idempotent write</b> — the primary key. A second {@code markTerminal} for the same day
 *       is a no-op rather than a duplicate-key exception.
 *   <li><b>Mutual exclusion WITHIN a JVM</b> — the {@code AtomicReference} CAS in
 *       {@code KiteAutoLoginService.claimAttempt}, <b>not</b> this table. ⚠️ A future editor who
 *       believed the old claim would conclude that CAS is redundant and delete it; it is the only
 *       thing serialising two monitor-pool threads.
 *   <li><b>Survival across restarts</b> — this table, and only for a verdict.
 * </ul>
 *
 * <p><b>Known residual, not a covered case:</b> a crash between the credential POST and
 * {@link #markTerminal} leaves no row, so a restarted process would resubmit. Closing it would mean
 * claiming the day BEFORE the attempt, which trades one hazard for a worse one — a crash
 * mid-attempt would then durably close a day on which nothing was ever rejected. Left open
 * deliberately.
 *
 * <p><b>{@code state = 'DONE'}, deliberately.</b> The CHECK constraint admits only {@code CLAIMED}
 * or {@code DONE} and a third value is not invented here. {@code CLAIMED} means "a door is
 * mid-publish right now, under a lease that a later door may steal" — exactly wrong for this row,
 * where nothing is in flight and nothing may ever steal it. {@code DONE} means "this day is
 * finished", which is precisely what a terminal refusal establishes, so {@code completed_at} is
 * stamped too.
 *
 * <p><b>⚠️ FAIL CLOSED.</b> Every read that throws is reported as {@link Verdict#UNAVAILABLE} and
 * the caller refuses the attempt. Falling through to the wire on the assumption that no row exists
 * would invert the entire guard: the one moment the database is unreachable is not the moment to
 * start guessing that yesterday's password rejection did not happen.
 */
public class AutoLoginTerminalLedger {

  /** What the durable record says about today. */
  enum Verdict {
    /** No terminal row for this IST date — an attempt may proceed. */
    PROCEED,
    /** A terminal refusal is already recorded for this IST date. */
    TERMINAL_RECORDED,
    /** The record could not be read. Treated as blocking — see the class javadoc. */
    UNAVAILABLE
  }

  /** {@code canary_runs.canary} key for this feature's per-IST-day terminal marker. */
  static final String CANARY_KEY = "KITE_AUTO_LOGIN_TERMINAL";

  private static final String STATE_DONE = "DONE";
  private static final String SOURCE_TAG = "KITE_AUTO_LOGIN";

  private static final Logger log = LoggerFactory.getLogger(AutoLoginTerminalLedger.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;

  AutoLoginTerminalLedger(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Factory for the live wiring, which sits in another package.
   *
   * <p>The constructor stays package-private so tests can build one directly without this class
   * having to expose anything else.
   */
  public static AutoLoginTerminalLedger forService(JdbcTemplate jdbc, Clock clock) {
    return new AutoLoginTerminalLedger(jdbc, clock);
  }

  /**
   * Whether an attempt may reach the wire for {@code day}.
   *
   * <p>ANY row under {@link #CANARY_KEY} blocks, not merely a {@code DONE} one. Only this class
   * writes that key and it only ever writes {@code DONE}, so a row in any other state means
   * something happened that this code does not model — and the safe reading of "I do not
   * understand this row" is to refuse, never to send a password.
   */
  Verdict verdictFor(LocalDate day) {
    try {
      Integer found =
          jdbc.queryForObject(
              "SELECT count(*) FROM canary_runs WHERE canary = ? AND run_day = ?",
              Integer.class,
              CANARY_KEY,
              day);
      return found != null && found > 0 ? Verdict.TERMINAL_RECORDED : Verdict.PROCEED;
    } catch (RuntimeException unreadable) {
      log.error(
          "kite auto-login: the terminal-day record for {} could NOT be read ({}) — refusing the"
              + " attempt rather than assuming the day is open",
          day,
          unreadable.getMessage());
      return Verdict.UNAVAILABLE;
    }
  }

  /**
   * Durably records that no further attempt may be made on {@code day}.
   *
   * <p>Idempotent by {@code ON CONFLICT DO NOTHING}: a second terminal refusal on the same day (the
   * transport chain's final failure, say) must not fail, and must not move {@code completed_at}
   * forward — the first refusal is the one that closed the day.
   *
   * <p>⚠️ A write failure is logged at ERROR and swallowed rather than propagated. The caller has
   * already set its in-process flag by this point, so the day is still blocked for this JVM; what
   * is lost is only the restart-survival property, and throwing here would turn a failed bookkeeping
   * write into a failed alert — the owner would stop being told the login is broken.
   */
  void markTerminal(LocalDate day) {
    try {
      jdbc.update(
          """
          INSERT INTO canary_runs (canary, run_day, state, source, claimed_at, completed_at)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT (canary, run_day) DO NOTHING
          """,
          CANARY_KEY,
          day,
          STATE_DONE,
          SOURCE_TAG,
          Timestamp.from(clock.instant()),
          Timestamp.from(clock.instant()));
    } catch (RuntimeException unwritable) {
      log.error(
          "kite auto-login: the terminal-day record for {} was NOT persisted ({}) — this JVM still"
              + " refuses further attempts, but a RESTART would not. Investigate before the next"
              + " session",
          day,
          unwritable.getMessage());
    }
  }
}

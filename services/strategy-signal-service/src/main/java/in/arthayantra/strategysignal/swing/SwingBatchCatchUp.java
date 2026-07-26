package in.arthayantra.strategysignal.swing;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The swing batch's missing CATCH-UP trigger (2026-07-17 incident). Ingest jobs replay on boot; the
 * swing batch never did — a stack down across the 20:00/20:05 IST window meant {@code @Scheduled}
 * simply never fired, and the open holdings' stops went un-evaluated with no retry (paper position 29
 * / OMAXAUTO sat open three days past its stop). {@link
 * in.arthayantra.strategysignal.signals.SwingBatchCanary} already DETECTED that at 08:30 next morning
 * but could only log an instruction to a human down an alert channel that was itself broken. This runs
 * the catch-up.
 *
 * <h2>Morning window, not boot</h2>
 *
 * <p>Both passes decide off a daily bar. Between a session's 15:30 close and the next 09:15 open, that
 * session's bar is final, so a run in that window reads exactly the bar the on-time 20:05 run would
 * have. The 08:35 sweep (after the 08:30 canary alert, before the open) gives the whole overnight
 * ingest chain time to land and re-attempts every trading day. Boot catch-up was rejected: it fires
 * once, at the moment inputs are least likely to exist (07-17 booted 22:14, screens landed 22:15).
 *
 * <h2>What it catches up, and how far back</h2>
 *
 * <p>It sweeps the {@code max-attempts + 2} most-recent trading days plus every retryable ledger row,
 * OLDEST first, so a multi-day outage cannot scroll an unfinished session out of the sweep. Each run
 * is pinned + TRUNCATED to its OWN daily bar ({@code SwingBatchEngine.truncateToSession}), so a stop
 * settles at the session's close whether the catch-up runs that night or days later. Per session:
 *
 * <ul>
 *   <li>Already recorded in {@code swing_batch_runs} (on-time OR a prior complete catch-up)? Skip.
 *   <li>Claim it ATOMICALLY in {@code swing_catchup_runs} BEFORE any emission — so two overlapping
 *       invocations (this cron racing a manual {@code POST /run}, or a crash-restart) cannot both emit
 *       and double a paper fill. Also hold the per-family {@link SwingRunMutex} across the sweep.
 *   <li>EXITS run unconditionally off the session bar (the money-critical part — a held stop needs no
 *       funnel). ENTRIES run only when the funnel is actually the session's screen (it silently serves
 *       the latest persisted screen, so entering off it would take the WRONG day's names).
 *   <li>Complete (every held stop evaluated) → record the {@code swing_batch_runs} marker (silences the
 *       canary) + mark the claim DONE. Partial (a held bar missing) → leave it retryable and try again
 *       next session. Exhausted its attempt budget → ABANDON + alert (bounded, never chases forever).
 * </ul>
 *
 * <p>Default-OFF ({@code artha.swing.catchup-enabled} / {@code ARTHA_SWING_CATCHUP_ENABLED}) — it fires
 * real entries and real paper exits, so arming it is the owner's call.
 */
@Component
public class SwingBatchCatchUp {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchCatchUp.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  /** A live run never approaches this; the daily cadence makes any RUNNING claim this old a crash. */
  private static final int STALE_LEASE_MINUTES = 30;

  private final SwingBatchRecorder recorder;
  private final SwingBatchRunRepository runs;
  private final SwingCatchUpStateRepository state;
  private final SwingBatchIntentRepository intents;
  private final SwingRunMutex mutex;
  private final List<SwingDoctrine> doctrines;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final boolean enabled;
  private final int maxAttempts;

  /** Wires the recorder, the marker + claim repos, the run mutex, every family doctrine, the bus, and the knobs. */
  public SwingBatchCatchUp(
      SwingBatchRecorder recorder,
      SwingBatchRunRepository runs,
      SwingCatchUpStateRepository state,
      SwingBatchIntentRepository intents,
      SwingRunMutex mutex,
      List<SwingDoctrine> doctrines,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${artha.swing.catchup-enabled:false}") boolean enabled,
      @Value("${artha.swing.catchup-max-attempts:5}") int maxAttempts) {
    this.recorder = recorder;
    this.runs = runs;
    this.state = state;
    this.intents = intents;
    this.mutex = mutex;
    this.doctrines = doctrines;
    this.events = events;
    this.clock = clock;
    this.enabled = enabled;
    this.maxAttempts = Math.max(1, maxAttempts);
  }

  /**
   * The morning sweep (08:35 IST weekdays — after the 08:30 canary alert, before the open).
   *
   * <p>Recovery is gated by the catch-up feature flag. The family arming decision is not read here:
   * schedulers persist it in {@code swing_batch_schedule_intents}, and each replay uses that
   * session-time row.
   *
   * <ul>
   *   <li><b>Recovery</b> (the money path — real entries/exits) runs only when the catch-up flag is ON.
   *       Schedule-time intent is checked before the atomic claim; missing intent refuses replay and
   *       records an explicit terminal reason.
   * </ul>
   */
  @Scheduled(
      cron = "${artha.swing.catchup-cron:0 35 8 * * MON-FRI}",
      zone = "Asia/Kolkata",
      scheduler = "swingCatchUpTaskScheduler")
  public void catchUp() {
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(IST));
    // GATE — never after the market-open deadline. The cron already places this at 08:35, but a cron override or a
    // future caller must not be able to fire an END-OF-DAY batch into a live session (a mid-session
    // "daily" bar is a partial one, and every decision here would price off it).
    if (marketOpenDeadlinePassed()) {
      log.warn("swing catch-up: past the market-open deadline ({}) — refusing", now.toLocalTime());
      return;
    }
    List<LocalDate> window;
    try {
      window = sessionWindow(now.toLocalDate());
    } catch (RuntimeException e) {
      // The bundled-calendar cliff — guarded exactly as the canary guards it, so a stale calendar
      // disables the sweep quietly rather than killing the @Scheduled tick.
      log.warn("swing catch-up: NSE calendar does not cover {} — skipping (calendar-cliff)", now.toLocalDate());
      return;
    }
    for (SwingDoctrine doctrine : doctrines) {
      if (!enabled) {
        continue; // recovery feature is off; leave all ledger rows for a later enabled sweep
      }
      // Hold the family run lock across its whole sweep so a manual POST /run cannot interleave and
      // double a fill. tryLock (not lock): a run already in flight means we simply retry next sweep,
      // never block the shared scheduler thread.
      ReentrantLock lock = mutex.lockFor(doctrine.batchName());
      if (!lock.tryLock()) {
        log.info("swing catch-up: {} run already in progress — skipping this sweep", doctrine.batchName());
        continue;
      }
      try {
        catchUpFamily(doctrine, window);
      } catch (RuntimeException e) {
        log.error("swing catch-up for {} failed: {}", doctrine.batchName(), e.getMessage(), e);
      } finally {
        lock.unlock();
      }
    }
  }

  /** The {@code max-attempts + 2} most-recent NSE trading sessions before {@code today}, OLDEST first. */
  private List<LocalDate> sessionWindow(LocalDate today) {
    List<LocalDate> out = new ArrayList<>();
    LocalDate d = calendar.previousTradingDay(today);
    for (int i = 0; i < maxAttempts + 2; i++) {
      out.add(d);
      d = calendar.previousTradingDay(d);
    }
    Collections.reverse(out);
    return out;
  }

  private void catchUpFamily(SwingDoctrine doctrine, List<LocalDate> window) {
    String batch = doctrine.batchName();
    List<LocalDate> retryable = state.retryableSessions(batch, STALE_LEASE_MINUTES);
    if (retryable == null) {
      retryable = List.of();
    }
    Set<LocalDate> sessions = new TreeSet<>(window);
    sessions.addAll(retryable);
    if (runs.lastRunDate(batch).isEmpty() && retryable.isEmpty()) {
      // Fresh marker table (fresh deploy) — no watermark means no evidence anything was MISSED, and a
      // catch-up on a blank slate would fire a full pass off an unowned history. (Same posture as the canary.)
      log.info("swing catch-up: {} has never recorded — skipping (fresh marker table)", doctrine.batchName());
      return;
    }
    for (LocalDate session : sessions) {
      if (marketOpenDeadlinePassed()) {
        log.warn(
            "swing catch-up: market opened during the {} sweep — leaving remaining sessions retryable",
            batch);
        return;
      }
      catchUpSession(doctrine, session, retryable.contains(session));
    }
  }

  private void catchUpSession(SwingDoctrine doctrine, LocalDate session, boolean retryable) {
    String batch = doctrine.batchName();
    if (runs.hasRun(batch, session)) {
      // A crash can leave the canonical marker committed while the catch-up ledger is still
      // RUNNING/PENDING. Reconcile that active row to terminal DONE; terminal rows are guarded by the
      // repository and remain ABANDONED/DISARMED.
      if (retryable) {
        state.markDone(batch, session);
      }
      return; // the on-time batch (or a prior complete catch-up) already ran this session
    }
    Optional<SwingBatchIntentRepository.Intent> intent = intents.find(batch, session);
    if (intent.isEmpty()) {
      state.markAbandoned(batch, session, "NO_SCHEDULE_INTENT");
      log.error("swing catch-up: {} {} refused â€” no schedule-time arming row", batch, session);
      alert(
          doctrine, "catch-up REFUSED for " + session,
          "The " + session + " batch has no schedule-time arming row (pre-V048 or capture failure);"
              + " refusing replay rather than inferring today's flag.");
      return;
    }
    if (!intent.get().armed()) {
      state.recordDisarmed(batch, session);
      log.info("swing catch-up: {} {} was DISARMED at schedule time â€” not replaying", batch, session);
      return;
    }
    // ATOMIC claim BEFORE any emission. Lost = another caller holds a fresh RUNNING claim, or the
    // session is terminal (DONE / ABANDONED). This is the durable idempotency gate the JVM mutex alone
    // cannot give (it must survive a crash between the money effect and the marker write).
    Optional<SwingCatchUpStateRepository.Claim> claim = state.claim(batch, session, STALE_LEASE_MINUTES);
    if (claim.isEmpty()) {
      return;
    }
    if (claim.get().attempts() > maxAttempts) {
      String reason = "ATTEMPT_BUDGET_EXHAUSTED after " + maxAttempts + " attempts";
      state.markAbandoned(batch, session, reason);
      log.error("swing catch-up: {} exhausted {} attempts for {} — abandoning", batch, maxAttempts, session);
      alert(
          doctrine, "catch-up ABANDONED for " + session,
          "Could not complete the " + session + " batch after " + maxAttempts + " attempts (a held"
              + " position's daily bar is still missing). Giving up — its stop for that session is"
              + " UNRECOVERABLE. Investigate the daily-candle gap, then POST /api/v1/signals/"
              + batch + "-swing/run if still needed.");
      return;
    }
    Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot = readCandidateSnapshot(doctrine);
    boolean entriesReady =
        candidateSnapshot.map(snapshot -> session.equals(snapshot.screenDate())).orElse(false);
    SwingBatchRecorder.RunOutcome outcome =
        recorder.runAndRecord(
            doctrine, session, entriesReady, SwingBatchRecorder.MarkerPolicy.ON_COMPLETE,
            candidateSnapshot);
    if (outcome == null) {
      // Compatibility for pre-snapshot test doubles. The real recorder always returns an outcome from
      // the snapshot-aware overload above.
      outcome =
          recorder.runAndRecord(doctrine, session, entriesReady, SwingBatchRecorder.MarkerPolicy.ON_COMPLETE);
    }
    SwingBatchEngine.SwingRun run = outcome.run();
    String summary =
        run.candidates() + " candidates, " + run.entries() + " entries, " + run.exits() + " exits, "
            + run.exitSkipped() + " exit-skipped";
    // DONE requires BOTH: every held stop evaluated AND the canonical swing_batch_runs marker actually
    // written. A marker-write failure (fail-soft-swallowed in the recorder) leaves hasRun false, so a
    // DONE claim here would strand the session forever (hasRun false → looks un-run, claim DONE → never
    // re-claimed). Keeping it PENDING lets the next sweep retry it (2026-07-17 review, Major).
    if (candidateSnapshot.isEmpty()) {
      state.markPending(batch, session);
      log.error(
          "swing catch-up: {} funnel snapshot failed for {} â€” exits may run, but the session stays"
              + " retryable and cannot be marked complete",
          batch, session);
      alert(
          doctrine, "catch-up INPUTS UNAVAILABLE for " + session,
          "The " + session + " batch could not read one immutable funnel snapshot. " + summary
              + "; the session stays retryable and will be attempted again.");
    } else if (run.exitSkipped() == 0 && outcome.markerRecorded()) {
      state.markDone(batch, session);
      log.warn("swing catch-up: {} caught up {} — {}", batch, session, summary);
      alert(
          doctrine, "catch-up ran for " + session,
          "The " + session + " batch never ran; caught up off that session's daily bar: " + summary
              + "." + (entriesReady ? "" : " Entries were SKIPPED (the funnel is not as-of " + session
                  + " — its screen never landed); the held stops WERE evaluated."));
    } else if (run.exitSkipped() == 0) {
      // Exits fully evaluated but the marker write FAILED — repairable, not terminal.
      state.markPending(batch, session);
      log.error(
          "swing catch-up: {} evaluated every stop for {} but the run-marker write FAILED — left"
              + " retryable so the next sweep repairs it",
          batch, session);
      alert(
          doctrine, "catch-up marker WRITE FAILED for " + session,
          "Caught up " + session + " (" + summary + ") but the swing_batch_runs marker did not persist"
              + " — the session is left retryable so the next sweep completes it. Check the DB.");
    } else {
      // A held anchor's daily bar is missing — the marker was NOT recorded; retry next session.
      state.markPending(batch, session);
      log.warn(
          "swing catch-up: {} PARTIAL for {} — {} stop(s) not evaluated, will retry", batch, session,
          run.exitSkipped());
      alert(
          doctrine, "catch-up INCOMPLETE for " + session,
          "Caught up what it could for " + session + " (" + summary + ") but " + run.exitSkipped()
              + " held stop(s) had no daily bar — leaving the session retryable for the next sweep.");
    }
  }

  /** True once the current trading day's 09:15 IST market-open deadline has passed. */
  private boolean marketOpenDeadlinePassed() {
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(IST));
    return calendar.isTradingDay(now.toLocalDate())
        && !now.toLocalTime().isBefore(MarketCalendar.SESSION_OPEN);
  }

  private Optional<SwingDoctrine.CandidateSnapshot> readCandidateSnapshot(SwingDoctrine doctrine) {
    SwingDoctrine.CandidateSnapshotRead read = doctrine.candidateSnapshot();
    if (read != null) {
      return read.snapshot();
    }
    // Compatibility for pre-snapshot test doubles. Concrete doctrines always return a non-null Optional.
    Optional<LocalDate> screenDate = doctrine.inputsAsOf();
    return Optional.of(
        new SwingDoctrine.CandidateSnapshot(screenDate == null ? null : screenDate.orElse(null), List.of()));
  }

  private void alert(SwingDoctrine doctrine, String title, String message) {
    try {
      events.publishEvent(
          new SwingBatchAlert(doctrine.batchName(), doctrine.alertLabel() + " " + title, message));
    } catch (RuntimeException e) {
      log.warn("{} swing catch-up alert publish failed: {}", doctrine.batchName(), e.getMessage());
    }
  }
}

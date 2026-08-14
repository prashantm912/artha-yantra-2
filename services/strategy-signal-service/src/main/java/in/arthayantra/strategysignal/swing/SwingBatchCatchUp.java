package in.arthayantra.strategysignal.swing;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRetry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
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
 * <h2>Morning window, not boot — and the ONE boot that is inside the morning window</h2>
 *
 * <p>Both passes decide off a daily bar. Between a session's 15:30 close and the next 09:15 open, that
 * session's bar is final, so a run in that window reads exactly the bar the on-time 20:05 run would
 * have. The 08:35 sweep (after the 08:30 canary alert, before the open) gives the whole overnight
 * ingest chain time to land and re-attempts every trading day.
 *
 * <p>⚠️ <b>Boot catch-up was rejected here, and the rejection was reasoned from an EVENING boot</b>
 * ("it fires once, at the moment inputs are least likely to exist — 07-17 booted 22:14, screens
 * landed 22:15"). That argument is still correct for an evening boot and {@link #catchUpIfMissed()}
 * still refuses one. <b>It does not transfer to a MORNING boot inside 08:35–09:15</b>: by then the
 * prior session's daily bar is final and its screen has been persisted since ~19:31 the night
 * before, so the inputs are not merely likely to exist — they are the same inputs the 08:35 cron
 * itself would have read.
 *
 * <p>And since the 16:00/08:35 split this sweep is the ONLY path that takes swing ENTRIES (the 16:00
 * pass runs {@code entries_enabled=f}), so a missed 08:35 fire is not a delayed recovery — it is a
 * whole session's entries forfeited. Measured 2026-08-14: the machine booted 08:38, three minutes
 * past the cron, {@code swing_catchup_runs} held no row at all for {@code session_date=2026-08-13},
 * and the pinned sweep — once triggered by hand — took 4 entries with 26 minutes to spare. This
 * machine is off overnight and boots ~08:00 on weekdays, so a boot after 08:35 is routine.
 *
 * <p>⚠️ <b>The missed fire leaves NO ROW to drain.</b> {@code swing_catchup_runs} is written by
 * {@link SwingCatchUpStateRepository#seedWindow} at the TOP of {@link #catchUp()}; a cron that never
 * ticked never seeded, so {@code retryableSessions} is empty and a boot path that only drained
 * existing rows would find nothing to do and reproduce the defect exactly. That is why the boot door
 * runs the WHOLE of {@code catchUp()} — seed included — rather than any narrower drain.
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
 *   <li>Complete (every held stop evaluated AND the entries taken) → record the {@code
 *       swing_batch_runs} marker (silences the canary) + mark the claim DONE. Partial (a held bar
 *       missing), or entries withheld because the funnel is not this session's screen → leave it
 *       retryable and try again next session. Exhausted its attempt budget → ABANDON + alert
 *       (bounded, never chases forever).
 *   <li>⚠️ DONE means "nothing left to do", and {@code claim} never re-claims a terminal row — so a
 *       session may not be marked DONE while it still owes ENTRIES, however completely its exits
 *       ran. Withheld entries are recoverable only for as long as the row stays retryable.
 * </ul>
 *
 * <p>Default-OFF ({@code artha.swing.catchup-enabled} / {@code ARTHA_SWING_CATCHUP_ENABLED}) — it fires
 * real entries and real paper exits, so arming it is the owner's call.
 */
@Component
@SuppressWarnings({"StringConcatToTextBlock", "ThreadJoinLoop"})
public class SwingBatchCatchUp {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchCatchUp.class);
  private static final ZoneId IST = ZoneOffset.ofHoursMinutes(5, 30);
  /** A live run never approaches this; the daily cadence makes any RUNNING claim this old a crash. */
  private static final int STALE_LEASE_MINUTES = 30;

  private final SwingBatchRecorder recorder;
  private final SwingBatchRunRepository runs;
  private final SwingCatchUpStateRepository state;
  private final SwingBatchIntentRepository intents;
  private final SwingPaperEffectRepository paperEffects;
  private final SignalRepository signals;
  private final SwingRunMutex mutex;
  private final List<SwingDoctrine> doctrines;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final boolean enabled;
  private final int maxAttempts;
  private final String cron;

  /** Wires the recorder, durable catch-up/effect repos, every family doctrine, the bus, and the knobs. */
  @Autowired
  public SwingBatchCatchUp(
      SwingBatchRecorder recorder,
      SwingBatchRunRepository runs,
      SwingCatchUpStateRepository state,
      SwingBatchIntentRepository intents,
      SwingPaperEffectRepository paperEffects,
      SignalRepository signals,
      SwingRunMutex mutex,
      List<SwingDoctrine> doctrines,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${artha.swing.catchup-enabled:false}") boolean enabled,
      @Value("${artha.swing.catchup-max-attempts:5}") int maxAttempts,
      // Same property + default as the @Scheduled below — catchUpIfMissed derives "has today's fire
      // already passed" from the SCHEDULE rather than a hardcoded 08:35, so the two cannot drift.
      // Invisible to OperatingWindowTest / CronPassthroughParityTest: both search for the literal
      // needle `cron = "${artha.swing.catchup-cron:`, which only the annotation carries.
      @Value("${artha.swing.catchup-cron:0 35 8 * * MON-FRI}") String cron) {
    this.recorder = recorder;
    this.runs = runs;
    this.state = state;
    this.intents = intents;
    this.paperEffects = paperEffects;
    this.signals = signals;
    this.mutex = mutex;
    this.doctrines = doctrines;
    this.events = events;
    this.clock = clock;
    this.enabled = enabled;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.cron = cron;
  }

  /**
   * Backwards-compatible constructor for focused catch-up tests that predate the V050 seams.
   *
   * <p>Pins the cron to Spring's {@code CRON_DISABLED}, deliberately: these doubles have no schedule,
   * so "was today's fire missed?" has no true answer for them and {@link #catchUpIfMissed()} declines.
   * They drive {@link #catchUp()} directly, which is unaffected. Repeating the real default here
   * would have made a fourth copy of a literal that must not drift.
   */
  public SwingBatchCatchUp(
      SwingBatchRecorder recorder,
      SwingBatchRunRepository runs,
      SwingCatchUpStateRepository state,
      SwingBatchIntentRepository intents,
      SwingPaperEffectRepository paperEffects,
      SwingRunMutex mutex,
      List<SwingDoctrine> doctrines,
      ApplicationEventPublisher events,
      Clock clock,
      boolean enabled,
      int maxAttempts) {
    this(
        recorder, runs, state, intents, paperEffects, null, mutex, doctrines, events, clock, enabled,
        maxAttempts, Scheduled.CRON_DISABLED);
  }

  /** Backwards-compatible constructor for focused catch-up tests that predate the V050 seams. */
  public SwingBatchCatchUp(
      SwingBatchRecorder recorder,
      SwingBatchRunRepository runs,
      SwingCatchUpStateRepository state,
      SwingBatchIntentRepository intents,
      SwingRunMutex mutex,
      List<SwingDoctrine> doctrines,
      ApplicationEventPublisher events,
      Clock clock,
      boolean enabled,
      int maxAttempts) {
    this(
        recorder, runs, state, intents, null, null, mutex, doctrines, events, clock, enabled,
        maxAttempts, Scheduled.CRON_DISABLED);
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
      Optional<LocalDate> lastRun = runs.lastRunDate(doctrine.batchName());
      List<LocalDate> pendingEffectSessions =
          paperEffects == null
              ? List.of()
              : paperEffects.pendingSessions(doctrine.batchName());
      if (lastRun.isEmpty() && pendingEffectSessions.isEmpty()) {
        // A blank marker table has no evidence that this deployment owns the rolling history. Do not
        // seed it now and accidentally replay those sessions after the first on-time marker lands.
        continue;
      }
      // SEED before taking the family mutex. A busy lock, a deadline return, or an exception before
      // the old claim point must not let a window session disappear without a retryable durable row.
      // A blank marker table is deliberately left untouched: there is no evidence this deployment
      // owns that history yet.
      if (lastRun.isPresent()) {
        state.seedWindow(doctrine.batchName(), window);
      }
      if (!pendingEffectSessions.isEmpty()) {
        state.seedPending(doctrine.batchName(), pendingEffectSessions);
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
        catchUpFamily(doctrine);
      } catch (RuntimeException e) {
        log.error("swing catch-up for {} failed: {}", doctrine.batchName(), e.getMessage(), e);
      } finally {
        lock.unlock();
      }
    }
  }

  /**
   * The BOOT door onto the SAME sweep — {@link SwingBatchBootCatchUp} calls this once per start.
   *
   * <p>Spring never replays a {@code @Scheduled} fire that ticked while the process was down, and
   * this machine boots ~08:00 on weekdays against an 08:35 cron, so a boot at 08:38 misses the only
   * pass that takes entries and forfeits them permanently (a later sweep withholds entries once the
   * funnel is no longer that session's screen, then burns attempt budget to ABANDONED).
   *
   * <p><b>Armed by exactly two conditions, and it delegates to {@link #catchUp()} for everything
   * else.</b> It adds NO new flag, NO second claim and NO alternate emission path: {@code
   * catchUp()}'s window seed, its {@code artha.swing.catchup-enabled} arming, the atomic per-{@code
   * (batch, session)} claim in {@code swing_catchup_runs} and the per-family {@link SwingRunMutex}
   * are the same objects on both doors, which is what makes an overlapping boot and cron emit at
   * most once between them.
   *
   * <ul>
   *   <li><b>Today's fire has already passed</b> — derived from the cron EXPRESSION, so the weekday
   *       clause comes free (on a Saturday the next fire is Monday ⇒ nothing was missed) and a boot
   *       at 07:00, before the cron, correctly leaves the job to the scheduler.
   *   <li><b>The market has not opened</b> — the same {@link #marketOpenDeadlinePassed()} the sweep
   *       itself gates on. This is what still refuses the 22:14 EVENING boot the original
   *       boot-catch-up rejection was reasoned from: at 22:14 on a trading day today's fire has
   *       passed, but the deadline has too, so the boot door declines and the 08:35 cron owns it.
   * </ul>
   *
   * @return true iff the sweep was actually run
   */
  public boolean catchUpIfMissed() {
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(IST));
    if (!scheduledFireHasPassed(now)) {
      return false;
    }
    if (marketOpenDeadlinePassed()) {
      // Not a failure: an END-OF-DAY batch may not price off a live (or post-open) session's partial
      // daily bar, which is the same reason catchUp() refuses here.
      log.info(
          "swing catch-up: booted {} IST, past the market-open deadline — today's missed '{}' fire"
              + " is NOT replayed on boot",
          now.toLocalTime(),
          cron);
      return false;
    }
    log.warn(
        "swing catch-up: booted {} IST, AFTER today's '{}' fire — replaying the missed sweep on boot",
        now.toLocalTime(),
        cron);
    catchUp();
    return true;
  }

  /**
   * Whether the catch-up cron's own fire for TODAY is at or before {@code now}.
   *
   * <p>Read off the configured expression rather than a hardcoded 08:35 so the boot door and the
   * schedule cannot drift, and so Spring's documented {@code "-"} disable value parses as
   * unschedulable ⇒ no boot catch-up — the right reading: a schedule that never fires cannot have
   * been missed.
   */
  private boolean scheduledFireHasPassed(ZonedDateTime now) {
    CronExpression expression;
    try {
      expression = CronExpression.parse(cron);
    } catch (IllegalArgumentException disabled) {
      log.info("swing catch-up: cron '{}' is not a schedule — no boot catch-up", cron);
      return false;
    }
    ZonedDateTime fire = expression.next(now.toLocalDate().atStartOfDay(IST).minusNanos(1));
    return fire != null && fire.toLocalDate().equals(now.toLocalDate()) && !fire.isAfter(now);
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

  private void catchUpFamily(SwingDoctrine doctrine) {
    String batch = doctrine.batchName();
    List<LocalDate> retryable = state.retryableSessions(batch, STALE_LEASE_MINUTES);
    if (retryable == null) {
      retryable = List.of();
    }
    Set<LocalDate> sessions = new TreeSet<>(retryable);
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
    // ⚠️ hasRunWithEntries, NOT hasRun (V060). Since the 16:00/08:35 split this sweep is no longer
    // only a recovery path — it is the ONLY path that takes entries, every session, because the
    // screen it needs lands somewhere between 17:52 and 19:30+ while the exits pass runs at 16:00.
    // That exits-only run writes a marker row, correctly (it evaluated every held stop, which is
    // what the 08:30 canary asks about), so a bare row-exists test would skip every single session
    // here and the book would never take another entry. The marker would be true and the inference
    // drawn from it false.
    if (runs.hasRunWithEntries(batch, session)) {
      if (retryable) {
        repairPendingEffects(batch, session);
        if (hasUnconfirmedPaperEffects(batch, session)) {
          state.markPending(batch, session, "PAPER_EFFECT_UNCONFIRMED");
          alert(
              doctrine,
              "catch-up PAPER EFFECTS UNCONFIRMED for " + session,
              "The canonical batch marker exists, but one or more paper effects are still CLAIMED;"
                  + " the session remains retryable and will be repaired on the next sweep.");
        } else {
          state.markDone(batch, session);
        }
      }
      return; // the on-time batch (or a prior complete catch-up) already ran this session
    }
    Optional<SwingBatchIntentRepository.Intent> row = intents.findIntent(batch, session);
    boolean markerProvesArmed = runs.hasRun(batch, session);
    // THREE states, not two, and collapsing them is what the last two review rounds kept finding.
    //
    //   ARMED       — a SETTLED row saying armed, or a run marker (only an armed run writes one, so
    //                 a marker outranks a provisional row in EITHER direction).
    //   DISARMED    — a SETTLED row saying disarmed. Terminal: the family was deliberately off.
    //   UNKNOWN     — a PROVISIONAL row only, with no marker. An intraday observation made hours
    //                 before the settle proves nothing about what the settle saw; the flag can move,
    //                 and the settle's own intent write is fail-soft so its absence is not evidence
    //                 either.
    //
    // ⚠️ A provisional FALSE must NOT be recorded terminally DISARMED. Round 5 did exactly that, so
    // a morning `false` + an armed settle whose intent write failed + a successful run marker still
    // forfeited every entry, permanently.
    Boolean armed = row.filter(SwingBatchIntentRepository.Intent::settled)
        .map(SwingBatchIntentRepository.Intent::armed)
        .orElse(null);
    if (armed == null && markerProvesArmed) {
      log.warn(
          "swing catch-up: {} {} has a run marker but no SETTLED arming row — the marker is proof"
              + " enough (only an armed run writes one; the intent write is fail-soft)",
          batch,
          session);
      armed = Boolean.TRUE;
    }
    if (armed == null && row.isEmpty()) {
      state.markAbandoned(batch, session, "NO_SCHEDULE_INTENT");
      log.error("swing catch-up: {} {} refused - no schedule-time arming row", batch, session);
      alert(
          doctrine, "catch-up REFUSED for " + session,
          "The " + session + " batch has no schedule-time arming row (pre-intent-ledger or capture"
              + " failure); refusing replay rather than inferring today's flag.");
      return;
    }
    if (Boolean.FALSE.equals(armed)) {
      state.recordDisarmed(batch, session);
      log.info("swing catch-up: {} {} was DISARMED at schedule time - not replaying", batch, session);
      return;
    }
    boolean armingUnknown = armed == null;
    if (armingUnknown) {
      // Provisional row, no marker: the settle never ran. Evaluate the held stops off this session's
      // own bar and take NO entries — you can always decline to enter, you cannot decline to leave.
      //
      // ⚠️ This pass must leave NO evidence behind. It writes no run marker (MarkerPolicy.NEVER) and
      // does not markDone, for two separate reasons found in round 5:
      //   - markDone excludes the session from retryableSessions, so the entry pass it is explicitly
      //     deferring would be forfeited by the very run that deferred it; and
      //   - an undifferentiated marker written HERE would be read by the NEXT sweep as proof of
      //     settle-time arming, so a recovery pass would manufacture the evidence authorising its
      //     own entries. A catch-up may not vouch for itself.
      // ⚠️ LOG the intent here; ALERT only after the run, from its outcome. An earlier version
      // published "Held stops WERE evaluated" at this point — before the atomic claim, the
      // attempt-budget check, the market-open deadline and the engine call itself. A lost claim, an
      // exhausted budget, a crossed deadline or exitSkipped > 0 all made that operator-facing
      // sentence false while it had already been sent (cross-vendor review, 2026-08-11). An alert
      // that asserts an outcome must be written BY the outcome.
      log.warn(
          "swing catch-up: {} {} has only a PROVISIONAL arming row and no run marker — attempting"
              + " EXITS ONLY, leaving the session retryable and writing no marker",
          batch,
          session);
    }
    // ATOMIC claim BEFORE any emission. Lost = another caller holds a fresh RUNNING claim, or the
    // session is terminal (DONE / ABANDONED). This is the durable idempotency gate the JVM mutex alone
    // cannot give (it must survive a crash between the money effect and the marker write).
    Optional<SwingCatchUpStateRepository.Claim> claim = state.claim(batch, session, STALE_LEASE_MINUTES);
    if (claim.isEmpty()) {
      return;
    }
    // ⚠️ FROM HERE ON THIS CALLER OWNS THE CLAIM, and that is the only window in which it may write
    // to the row. An earlier version put this handler at the CALL SITE, outside this method — which
    // meant a failure BEFORE the claim (or one on a pass whose claim was LOST to another caller)
    // still ran markPending, flipping a row another invocation was holding as RUNNING back to
    // PENDING. That releases someone else's claim mid-flight and lets a third invocation claim and
    // emit concurrently: the doubled entry and averaged paper position this durable gate exists to
    // prevent (PaperService averages a second open on the same key rather than rejecting it).
    // Cross-vendor review Critical — and my own test could not have caught it, because its fixture
    // guarantees this caller wins the claim.
    try {
      runClaimedSession(doctrine, session, batch, claim.get(), armingUnknown);
    } catch (RuntimeException failed) {
      // Safe now: we hold the claim, so PENDING is ours to write. Without this the row keeps its
      // PREVIOUS reason and the ABANDONED alert would name a stale one.
      state.markPending(batch, session, "EXECUTION_FAILED");
      throw failed;
    }
  }

  /** The claimed body. Every write in here is legitimate: the caller holds this session's claim. */
  private void runClaimedSession(
      SwingDoctrine doctrine,
      LocalDate session,
      String batch,
      SwingCatchUpStateRepository.Claim heldClaim,
      boolean armingUnknown) {
    Optional<SwingCatchUpStateRepository.Claim> claim = Optional.of(heldClaim);
    if (claim.get().attempts() > maxAttempts) {
      String reason = "ATTEMPT_BUDGET_EXHAUSTED after " + maxAttempts + " attempts";
      state.markAbandoned(batch, session, reason);
      log.error("swing catch-up: {} exhausted {} attempts for {} — abandoning", batch, maxAttempts, session);
      // ⚠️ CAUSE-NEUTRAL, deliberately. This message used to assert one cause ("a held position's
      // daily bar is still missing") and recommend POST /run. Both were safe only while the budget
      // had exactly one way to run out. It now has a second: a session whose SCREEN never landed
      // stays retryable and burns the budget with its exits fully evaluated and nothing wrong with
      // any bar — so the stated cause would be flatly wrong half the time.
      //
      // The remediation was worse than the diagnosis. `/run` is UNPINNED: it calls
      // runAndRecord(doctrine) with no session, so it enters off the LATEST funnel
      // (ManasAroraSwingController / MinerviniSwingController). For a screen-mismatch abandonment
      // that is precisely the wrong thing — it cannot restore the missing historical entries, and
      // it would take a different day's names while appearing to fix the problem.
      alert(
          doctrine, "catch-up ABANDONED for " + session,
          "Could not complete the " + session + " batch after " + maxAttempts + " attempts. Giving"
              + " up — whatever that session still owed is now UNRECOVERABLE by the catch-up."
              + " Check swing_catchup_runs.reason for this session before acting: a missing"
              + " daily bar, an unreadable funnel and a screen that never landed need different"
              + " fixes. Every retryable failure that returns normally records a reason; a hard"
              + " crash cannot, so correlate the last recorded reason with the service and"
              + " container logs."
              + " ⚠️ Do NOT reach for POST /api/v1/signals/" + batch + "-swing/run to repair this."
              + " That endpoint is UNPINNED — it runs against TODAY's funnel, so it cannot recover"
              + " a past session's entries and may take an entirely different set of names.");
      return;
    }
    if (marketOpenDeadlinePassed()) {
      state.markPending(batch, session, "MARKET_OPEN_DEADLINE");
      return;
    }
    repairPendingEffects(batch, session);
    Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot = readCandidateSnapshot(doctrine);
    // TWO independent conditions, and both must hold before this sweep enters anything:
    //   - the funnel must actually be THIS session's screen (it silently serves the latest persisted
    //     one, so entering off it would take the wrong day's names); and
    //   - the arming must be AUTHORITATIVE, not a provisional intraday observation (V061).
    boolean entriesReady =
        !armingUnknown
            && candidateSnapshot.map(snapshot -> session.equals(snapshot.screenDate())).orElse(false);
    // ⚠️ NEVER, not ON_COMPLETE, when the arming is unknown: this pass must not leave a marker the
    // NEXT sweep would read as proof of settle-time arming. A catch-up may not vouch for itself.
    SwingBatchRecorder.MarkerPolicy markerPolicy =
        armingUnknown
            ? SwingBatchRecorder.MarkerPolicy.NEVER
            : SwingBatchRecorder.MarkerPolicy.ON_COMPLETE;
    SwingBatchRecorder.RunOutcome outcome =
        recorder.runAndRecord(
            doctrine,
            session,
            entriesReady,
            markerPolicy,
            candidateSnapshot,
            this::marketOpenDeadlinePassed);
    if (outcome == null) {
      // Compatibility for pre-snapshot test doubles. The real recorder always returns an outcome from
      // the snapshot-aware overload above.
      outcome =
          recorder.runAndRecord(
              doctrine,
              session,
              entriesReady,
              SwingBatchRecorder.MarkerPolicy.ON_COMPLETE,
              candidateSnapshot);
    }
    if (outcome == null) {
      // Compatibility for recorder doubles from before the immutable snapshot seam.
      outcome =
          recorder.runAndRecord(
              doctrine, session, entriesReady, SwingBatchRecorder.MarkerPolicy.ON_COMPLETE);
    }
    SwingBatchEngine.SwingRun run = outcome.run();
    String summary =
        run.candidates() + " candidates, " + run.entries() + " entries, " + run.exits() + " exits, "
            + run.exitSkipped() + " exit-skipped, " + run.refusalReasons().size() + " refusal(s)";
    if (run.deadlineReached()) {
      state.markPending(batch, session, "MARKET_OPEN_DEADLINE");
      alert(
          doctrine,
          "catch-up DEADLINE for " + session,
          "The market-open deadline crossed before the next money effect; the current claim remains"
              + " retryable and no completeness marker was accepted.");
      return;
    }
    repairPendingEffects(batch, session);
    boolean paperEffectsConfirmed = !hasUnconfirmedPaperEffects(batch, session);
    // DONE requires BOTH: every held stop evaluated AND the canonical swing_batch_runs marker actually
    // written. A marker-write failure (fail-soft-swallowed in the recorder) leaves hasRun false, so a
    // DONE claim here would strand the session forever (hasRun false → looks un-run, claim DONE → never
    // re-claimed). Keeping it PENDING lets the next sweep retry it (2026-07-17 review, Major).
    if (candidateSnapshot.isEmpty()) {
      state.markPending(batch, session, "INPUTS_UNAVAILABLE");
      log.error(
          "swing catch-up: {} funnel snapshot failed for {} â€” exits may run, but the session stays"
              + " retryable and cannot be marked complete",
          batch, session);
      alert(
          doctrine, "catch-up INPUTS UNAVAILABLE for " + session,
          "The " + session + " batch could not read one immutable funnel snapshot. " + summary
              + "; the session stays retryable and will be attempted again.");
    } else if (!paperEffectsConfirmed) {
      state.markPending(batch, session, "PAPER_EFFECT_UNCONFIRMED");
      log.error(
          "swing catch-up: {} paper effect confirmation incomplete for {} — leaving retryable",
          batch,
          session);
      alert(
          doctrine,
          "catch-up PAPER EFFECTS UNCONFIRMED for " + session,
          "The batch produced " + summary
              + ", but at least one expected paper effect is not CONFIRMED; repair will be retried.");
    } else if (!run.refusalReasons().isEmpty()) {
      String reason = run.refusalReasons().get(0);
      state.markPending(batch, session, reason);
      alert(
          doctrine,
          "catch-up REFUSED for " + session,
          "The session remains retryable because the engine refused an approximate money effect: "
              + reason + ". " + summary);
    } else if (armingUnknown) {
      // Exits ran, entries deliberately did not, and no marker was written. The session is NOT done
      // — it still owes its entries — so it stays PENDING for a later sweep that finds an
      // authoritative arming. markDone here would have forfeited the very pass this run deferred.
      state.markPending(batch, session, "ARMING_UNKNOWN_EXITS_ONLY");
      log.warn(
          "swing catch-up: {} ran {}'s exits with UNKNOWN arming — left retryable, entries still"
              + " owed",
          batch,
          session);
      alert(
          doctrine,
          "catch-up EXITS ONLY for " + session,
          "The " + session + " arming is provisional (an intraday observation, not the settle's own"
              + " reading) and no run marker exists, so entries were withheld and the session stays"
              + " retryable. "
              + (run.exitSkipped() == 0
                  ? "Every held stop WAS evaluated off that session's bar."
                  : run.exitSkipped()
                      + " held stop(s) were NOT evaluated — see the STOP NOT EVALUATED TODAY errors"
                      + " in the service log."));
    } else if (!entriesReady && run.exitSkipped() == 0 && outcome.markerRecorded()) {
      // ⚠️ The SAME argument as the armingUnknown branch directly above, for the OTHER reason
      // entries get withheld: the funnel served is not this session's screen, so entering off it
      // would take the wrong day's names. Exits ran; the entries are still OWED.
      //
      // This branch used to fall through to markDone, and the alert text on that line said "Entries
      // were SKIPPED" while the row went TERMINAL — and `claim` never re-claims a terminal row, so a
      // screen landing later could not recover them. Silent, permanent forfeiture of a session's
      // entries, distinguishable from a clean run only by reading the alert body.
      //
      // Failure direction is deliberately inverted: a session whose screen NEVER lands now consumes
      // its attempt budget until ABANDONED, which alerts the owner. A loud unrecoverable beats a
      // silent one.
      state.markPending(batch, session, "SCREEN_NOT_AS_OF_SESSION");
      log.warn(
          "swing catch-up: {} ran {}'s exits but the funnel is not as-of that session — left"
              + " retryable, entries still owed",
          batch,
          session);
      alert(
          doctrine,
          "catch-up EXITS ONLY for " + session,
          "The " + session + " screen never landed, so the funnel serves another session's names and"
              + " entries were withheld. Its entries are still owed, so the session stays retryable"
              + " rather than being marked done without them. " + summary
              + ". Every held stop WAS evaluated off that session's bar.");
    } else if (run.exitSkipped() == 0 && outcome.markerRecorded()) {
      state.markDone(batch, session);
      log.warn("swing catch-up: {} caught up {} — {}", batch, session, summary);
      alert(
          doctrine, "catch-up ran for " + session,
          "The " + session + " batch never ran; caught up off that session's daily bar: " + summary
              + ".");
    } else if (run.exitSkipped() == 0) {
      // Exits fully evaluated but the marker write FAILED — repairable, not terminal.
      state.markPending(batch, session, "RUN_MARKER_WRITE_FAILED");
      log.error(
          "swing catch-up: {} evaluated every stop for {} but the run-marker write FAILED — left"
              + " retryable so the next sweep repairs it",
          batch, session);
      alert(
          doctrine, "catch-up marker WRITE FAILED for " + session,
          "Caught up " + session + " (" + summary + ") but the swing_batch_runs marker did not persist"
              + " — the session is left retryable so the next sweep completes it. Check the DB."
              + (entriesReady
                  ? ""
                  : " Its ENTRIES are also still owed: the funnel was not as-of that session, so a"
                      + " later sweep needs its screen, not just the marker."));
    } else {
      // A held anchor's daily bar is missing — the marker was NOT recorded; retry next session.
      state.markPending(batch, session, "DAILY_BAR_MISSING");
      log.warn(
          "swing catch-up: {} PARTIAL for {} — {} stop(s) not evaluated, will retry", batch, session,
          run.exitSkipped());
      alert(
          doctrine, "catch-up INCOMPLETE for " + session,
          "Caught up what it could for " + session + " (" + summary + ") but " + run.exitSkipped()
              + " held stop(s) had no daily bar — leaving the session retryable for the next sweep."
              + (entriesReady
                  ? ""
                  : " Its ENTRIES are also still owed: the funnel was not as-of that session, so"
                      + " recovery needs its screen as well as the missing bar."));
    }
  }

  /** Reconciles visible paper effects and replays unresolved claimed effects through the event bus. */
  private void repairPendingEffects(String batch, LocalDate session) {
    if (paperEffects == null) {
      return;
    }
    if (marketOpenDeadlinePassed()) {
      return;
    }
    List<SwingPaperEffectRepository.Effect> pending;
    try {
      pending = paperEffects.repairable(batch, session);
    } catch (RuntimeException e) {
      log.error("swing catch-up: paper-effect ledger read failed for {} {}", batch, session, e);
      return;
    }
    for (SwingPaperEffectRepository.Effect effect : pending) {
      if (marketOpenDeadlinePassed()) {
        return;
      }
      try {
        // Recovery is deliberately stricter than the original event path: an UNDECIDED or SKIPPED
        // row carries no permission to infer a money effect. The repository also filters these rows,
        // but keep the guard here so a stale/legacy repository implementation cannot widen replay.
        if (!"REQUIRED".equals(effect.decision())) {
          continue;
        }
        boolean alreadyApplied =
            "ENTRY".equals(effect.effectType())
                ? effect.signalId() != null && paperEffects.entryConfirmedByPaper(effect.signalId())
                : !effect.targetPositionIds().isEmpty()
                    && paperEffects.exitConfirmedByPaper(effect.targetPositionIds());
        if (alreadyApplied) {
          if ("ENTRY".equals(effect.effectType())) {
            paperEffects.confirmEntry(effect.signalId());
          } else {
            paperEffects.confirmExit(effect.anchorSignalId());
          }
          continue;
        }
        if ("ENTRY".equals(effect.effectType())) {
          if (signals == null || effect.signalId() == null) {
            continue;
          }
          signals
              .find(effect.signalId())
              .filter(row -> row.suggestedQty() != null && row.suggestedQty().intValue() > 0)
              .ifPresent(
                  row ->
                      events.publishEvent(
                          SwingPaperEffectRetry.entry(
                              batch,
                              session,
                              row.id(),
                              row.suggestedQty().intValue(),
                              row.entryPrice(),
                              row.scalperDetail() != null)));
        } else if (effect.anchorSignalId() != null
            && effect.exitSignalId() != null
            && !effect.targetPositionIds().isEmpty()) {
          events.publishEvent(
              SwingPaperEffectRetry.exit(
                  batch,
                  session,
                  effect.anchorSignalId(),
                  effect.exitSignalId(),
                  effect.closeReason(),
                  effect.closePrice()));
        }
      } catch (RuntimeException e) {
        log.warn(
            "swing catch-up: paper effect {} repair failed: {}", effect.effectKey(), e.getMessage());
      }
    }
  }

  /** Ledger read failure is fail-closed: an unverifiable paper effect cannot permit DONE. */
  private boolean hasUnconfirmedPaperEffects(String batch, LocalDate session) {
    if (paperEffects == null) {
      return false;
    }
    try {
      return !paperEffects.allConfirmed(batch, session);
    } catch (RuntimeException e) {
      log.error("swing catch-up: paper-effect ledger verification failed for {} {}", batch, session, e);
      return true;
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

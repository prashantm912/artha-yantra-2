package in.arthayantra.strategysignal.swing;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.signals.FlagSnapshotService;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository.Pass;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The ONE run path for every swing family (audit P0-4/H10, consolidating the per-family recorders):
 * engine → {@code swing_batch_runs} marker → summary alert. Both the family scheduler AND the
 * on-demand {@code POST /run} go through {@link #runAndRecord} so a manual catch-up run records its
 * marker (else the did-not-run canary keeps alerting for a date the owner already ran). The run date
 * is captured BEFORE the engine runs (a past-IST-midnight completion must not stamp the next day). A
 * flag-off no-op run records/pushes nothing.
 *
 * <p>{@link #runAndRecord} lets engine exceptions PROPAGATE — the controller turns them into a 500.
 * {@link #runScheduled} wraps it so the SCHEDULER turns a throw into a FAILED ops alert instead of a
 * lone log line.
 */
@Component
public class SwingBatchRecorder {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchRecorder.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  /** Field, not injected — the same shape SwingBatchCatchUp uses for its own session window. */
  private static final MarketCalendar CALENDAR = MarketCalendar.nse();

  /**
   * When the {@code swing_batch_runs} completeness marker is stamped. The scheduled / on-demand path
   * ALWAYS records (its historical behaviour). The catch-up records ON_COMPLETE — only when the exit
   * pass fully evaluated ({@code exitSkipped == 0}) — so a partial catch-up (a held anchor's bar was
   * missing) does NOT mark the session done and stays retryable (2026-07-17 review, Critical 1).
   */
  public enum MarkerPolicy {
    ALWAYS,
    ON_COMPLETE,
    /**
     * Write NO marker, whatever the run did.
     *
     * <p>For a recovery pass that must leave no evidence behind: the catch-up's exits-only run on an
     * UNKNOWN arming. A marker written there is undifferentiated, so the next sweep would read it as
     * proof of settle-time arming and enter on the strength of a run that was explicitly deferring
     * entries — a catch-up manufacturing the evidence that authorises itself (cross-vendor review,
     * 2026-08-10). The cost is that the did-not-run canary keeps alerting for that session, which is
     * correct: the session genuinely is not done.
     */
    NEVER
  }

  /**
   * The result of a run: the {@link SwingBatchEngine.SwingRun} plus whether the canonical {@code
   * swing_batch_runs} completeness marker was actually written. The catch-up marks a session terminally
   * DONE only when BOTH the exit pass fully evaluated AND {@code markerRecorded} — a marker write that
   * fail-soft-swallowed must leave the session repairable, never stuck DONE-without-marker (2026-07-17
   * review Major).
   */
  public record RunOutcome(SwingBatchEngine.SwingRun run, boolean markerRecorded) {}

  private final SwingBatchEngine engine;
  private final SwingBatchRunRepository runs;
  private final SwingSellDecisionService sellDecisions;
  private final FlagSnapshotService flagSnapshots;
  private final SwingRunMutex mutex;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  /** Wires the shared engine, the marker repo, the sell-decision store, the flag ledger, the run mutex, and the bus. */
  public SwingBatchRecorder(
      SwingBatchEngine engine,
      SwingBatchRunRepository runs,
      SwingSellDecisionService sellDecisions,
      FlagSnapshotService flagSnapshots,
      SwingRunMutex mutex,
      ApplicationEventPublisher events,
      Clock clock) {
    this.engine = engine;
    this.runs = runs;
    this.sellDecisions = sellDecisions;
    this.flagSnapshots = flagSnapshots;
    this.mutex = mutex;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Runs one daily batch for a family, records the marker, and pushes the summary. Propagates.
   *
   * <p>The manual {@code POST /api/v1/signals/&lt;batch&gt;-swing/run} path, and it DECLARES
   * {@link Pass#ENTRIES} — see that constant for why a hand-run legitimately shares the
   * catch-up's pass value rather than taking a fourth of its own. {@code sessionDate} is null,
   * so {@link #runLocked} stamps the marker under TODAY, never a past session.
   */
  public SwingBatchEngine.SwingRun runAndRecord(SwingDoctrine doctrine) {
    return runAndRecord(doctrine, null, Pass.ENTRIES, MarkerPolicy.ALWAYS).run();
  }

  /**
   * Runs one daily batch PINNED to a past session ({@link SwingBatchCatchUp}), or the ordinary
   * same-evening batch when {@code sessionDate} is null. The whole run is serialized per family via
   * {@link SwingRunMutex} so it can never emit concurrently with another run path (a manual
   * {@code POST /run} on a request thread) and double a fill.
   *
   * <p>Three things are load-bearing on the pinned catch-up path:
   *
   * <ol>
   *   <li>The {@code swing_batch_runs} marker is stamped under the SESSION's date, not today's. Stamping
   *       today would tell the P0-4 canary that today's batch ran — masking the NEXT miss — while leaving
   *       the session it actually caught up still looking missed.
   *   <li>The engine is pinned to that session's daily bar (see {@code SwingBatchEngine#runDaily}), so a
   *       catch-up can only ever decide off the bar the on-time run would have read.
   *   <li>{@code markerPolicy=ON_COMPLETE} records the marker only when the exit pass fully evaluated, so
   *       a partial run stays retryable rather than being recorded "done" (Critical 1).
   * </ol>
   */
  public RunOutcome runAndRecord(
      SwingDoctrine doctrine, LocalDate sessionDate, Pass pass, MarkerPolicy markerPolicy) {
    ReentrantLock lock = mutex.lockFor(doctrine.batchName());
    lock.lock();
    try {
      if (!doctrine.enabled()) {
        return runLocked(
            doctrine, sessionDate, pass, markerPolicy, null, false, null);
      }
      // ⚠️ An EXITS-ONLY run must not read the entry funnel at all. Passing a snapshot makes
      // runLocked's `snapshotAvailable` gate depend on it, so a funnel failure would refuse the run
      // MARKER even though the exit pass ran to completion — and the 20:15 heartbeat then withholds
      // its ping over a funnel the run never needed. Cross-vendor review, 2026-08-10; it matters now
      // because the 16:00 settle is exits-only every single session, so that would not be an edge
      // case but the normal path. null means "no snapshot was consulted", which is the truth here.
      java.util.Optional<SwingDoctrine.CandidateSnapshot> snapshot = null;
      if (pass.takesEntries()) {
        SwingDoctrine.CandidateSnapshotRead read = doctrine.candidateSnapshot();
        snapshot = read == null ? null : read.snapshot();
      }
      return runLocked(
          doctrine, sessionDate, pass, markerPolicy, snapshot, doctrine.enabled(), null);
    } finally {
      lock.unlock();
    }
  }

  /** Runs from the exact funnel snapshot already read by the catch-up readiness gate. */
  public RunOutcome runAndRecord(
      SwingDoctrine doctrine,
      LocalDate sessionDate,
      Pass pass,
      MarkerPolicy markerPolicy,
      Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot) {
    ReentrantLock lock = mutex.lockFor(doctrine.batchName());
    lock.lock();
    try {
      // This overload is used by catch-up after it has read the historical schedule intent. The
      // current doctrine flag may have changed since that session, so it is deliberately not the gate.
      return runLocked(doctrine, sessionDate, pass, markerPolicy, candidateSnapshot, true, null);
    } finally {
      lock.unlock();
    }
  }

  /** Runs from the catch-up snapshot with a deadline checked before the marker is written. */
  public RunOutcome runAndRecord(
      SwingDoctrine doctrine,
      LocalDate sessionDate,
      Pass pass,
      MarkerPolicy markerPolicy,
      Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot,
      SwingBatchEngine.RunDeadline deadline) {
    ReentrantLock lock = mutex.lockFor(doctrine.batchName());
    lock.lock();
    try {
      return runLocked(
          doctrine, sessionDate, pass, markerPolicy, candidateSnapshot, true, deadline);
    } finally {
      lock.unlock();
    }
  }

  /**
   * The session this settle must price off — the PIN that stops a stop being evaluated against a
   * bar that is not this session's (ledger H27).
   *
   * <p>⚠️ This used to be {@code null}, which means "settle off whatever the newest bar happens to
   * be". That reads as harmless and is not: most cash equities have no intraday 1d bar at all, so
   * before the settle moved past the 18:45 bhavcopy ingest the newest bar was routinely the
   * PREVIOUS session's. Measured 2026-08-18 across the whole table's lifetime — 69 of 417 minervini
   * and 17 of 169 manas-arora persisted rows were computed on an earlier session's bar, and on
   * 08-14/08-17/08-18 it was 14 of 15 every day. Nothing counted it: {@code exitSkipped} covers an
   * EMPTY series and an out-of-window entry bar, not a series that ends on the wrong day, so the
   * batch logged "0 exits, 0 exit-skipped" over stale input.
   *
   * <p>With the pin, {@code truncateToSession} drops a series that has no bar for this session, the
   * exit pass retries once, and a still-missing bar becomes {@code exitSkipped} + a
   * STOP-NOT-EVALUATED error — which keeps the session retryable by the 08:35 catch-up rather than
   * silently settled. Skipping is the SAFE direction here: the catch-up settles off the session's
   * own bar, whereas a stale evaluation is wrong in a direction nobody can see.
   *
   * <p>⚠️ <b>Holidays: EMPTY, not the previous session.</b> The crons are MON-FRI, which includes
   * NSE holidays. The first cut of this change resolved a holiday BACK to the previous trading day
   * on the reasoning that re-evaluating it is the same no-op the batch already performed. That is
   * true of the exit evaluation and FALSE of everything written alongside it, which is what makes it
   * a trap rather than a harmless nicety (cross-vendor review of this PR, 2026-08-20):
   *
   * <ul>
   *   <li>The run marker is keyed {@code (batch, run_date)} and its upsert REPLACES the counters
   *       ({@code SwingBatchRunRepository:56-70}) — so a holiday run would overwrite the previous
   *       session's real {@code entries}/{@code exits}/{@code exit_skipped}/admission probe with a
   *       no-op run's. The evidence for the session that actually traded is destroyed.
   *   <li>The scheduler records its intent under TODAY ({@code MinerviniSwingScheduler:115}), so the
   *       holiday would hold an intent row with no marker of its own. {@link SwingBatchCanary}
   *       sweeps exactly that shape and pages "DID NOT RUN" — and its own comment notes a past
   *       session "can never acquire a run marker", so the page repeats to its ceiling. A false
   *       alarm promising a catch-up that only ever traverses TRADING sessions.
   * </ul>
   *
   * <p>There is no new close on a holiday, the previous session was settled on its own evening, and
   * a genuinely missed session is what the 08:35 catch-up exists for. So the honest answer is that a
   * holiday is not a settle session at all. Callers skip; {@link #runScheduled} pins today so a
   * MANUAL run on a holiday still behaves as it did pre-fix (no bar, {@code exitSkipped}, visible).
   *
   * <p>Fail-open to today past the bundled calendar's horizon: refusing to settle would be a far
   * worse failure than settling off the newest bar, which is exactly the pre-fix behaviour.
   */
  public Optional<LocalDate> scheduledSettleSession() {
    LocalDate today = LocalDate.now(clock.withZone(IST));
    try {
      return CALENDAR.isTradingDay(today) ? Optional.of(today) : Optional.empty();
    } catch (RuntimeException e) {
      log.warn(
          "swing settle: NSE calendar does not cover {} — pinning today unresolved (calendar-cliff):"
              + " {}",
          today, e.getMessage());
      return Optional.of(today);
    }
  }

  private RunOutcome runLocked(
      SwingDoctrine doctrine,
      LocalDate sessionDate,
      Pass pass,
      MarkerPolicy markerPolicy,
      Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot,
      boolean executionArmed,
      SwingBatchEngine.RunDeadline deadline) {
    // ⚠️ ONE operand for both halves. `pass` is what the CALLER declared this run to be; the
    // engine's entry gate is DERIVED from it rather than travelling alongside as a second
    // boolean, so the marker can never say ENTRIES about a run the engine executed exits-only —
    // nor SETTLE about the catch-up's recovery pass, which is the failure V063's first cut
    // shipped (cross-vendor review, 2026-08-25).
    boolean entriesEnabled = pass.takesEntries();
    LocalDate runDate = sessionDate != null ? sessionDate : LocalDate.now(clock.withZone(IST));
    SwingBatchEngine.SwingRun result =
        candidateSnapshot == null
            ? deadline == null
                ? engine.runDaily(doctrine, sessionDate, entriesEnabled)
                : engine.runDaily(doctrine, sessionDate, entriesEnabled, deadline)
            : deadline == null
                ? engine.runDaily(doctrine, sessionDate, entriesEnabled, candidateSnapshot, executionArmed)
                : engine.runDaily(
                    doctrine, sessionDate, entriesEnabled, candidateSnapshot, executionArmed, deadline);
    if (!executionArmed) {
      return new RunOutcome(result, false); // disarmed no-op — nothing ran, nothing recorded
    }
    // P1-7: snapshot the out-of-YAML flag regime that governed THIS run (the pyramiding env flags) keyed to
    // the swing_batch_runs natural key (batch:runDate). capture() is fail-soft — an observability write must
    // never break the run this batch is the only exit evaluator for.
    Map<String, Object> flags = new TreeMap<>();
    flags.put("batch", doctrine.batchName());
    doctrine.pyramid().describe().forEach((k, v) -> flags.put("pyramid." + k, v));
    flagSnapshots.capture(
        FlagSnapshotService.SWING_BATCH, doctrine.batchName() + ":" + runDate, doctrine.book(), flags);
    SwingBatchEngine.AdmissionProbe probe = result.admission();
    boolean markerRecorded = false;
    boolean snapshotAvailable = candidateSnapshot == null || candidateSnapshot.isPresent();
    if (markerPolicy != MarkerPolicy.NEVER
        && snapshotAvailable
        && !result.deadlineReached()
        && result.refusalReasons().isEmpty()
        && (markerPolicy == MarkerPolicy.ALWAYS || result.exitSkipped() == 0)) {
      try {
        markerRecorded =
            runs.record(
                doctrine.batchName(), runDate, result.strategies(), result.candidates(),
                result.entries(), result.exits(), result.exitSkipped(), probe.openAtStart(),
                probe.wouldEnter(), probe.admitted(), probe.capExceedance(), probe.capBound(),
                probe.droppedByCap(), pass);
      } catch (RuntimeException e) {
        log.warn("{} swing run-marker record failed: {}", doctrine.batchName(), e.getMessage());
      }
    }
    // Persist the sell-decision triad snapshot (V037) — fail-soft: this batch is the swing positions'
    // only exit evaluator, and the entry/exit passes already committed above, so a persist defect must
    // never propagate out of the run path (mirrors the run-marker record's own fail-soft envelope).
    try {
      int rows = sellDecisions.persist(doctrine);
      log.info("{} swing: persisted {} sell-decision row(s)", doctrine.batchName(), rows);
    } catch (RuntimeException e) {
      log.warn("{} swing sell-decision persist failed: {}", doctrine.batchName(), e.getMessage());
    }
    // A governor-bound run reads as "N candidates, 0 entries" — indistinguishable from a dead batch
    // unless the probe is spelled out. Appended only when something actually shed a would-be entrant,
    // so an ordinary run's alert text is unchanged.
    // ⚠️ SAY "entry governor", NOT "slot cap" (review 2026-08-08). capBound is capExceedance > 0, i.e.
    // wouldEnter - admitted, and that gap is opened by ANY of entryVeto's six rails (KILL_SWITCH,
    // MAX_OPEN, DAILY_LOSS, DAILY_PROFIT_TARGET, MAX_DEPLOYMENT_PCT, HEAT_CAP_PCT) as well as the M40
    // portfolio open-risk skip. Naming the slot cap here would send the owner to free a slot on a book
    // that had merely banked its daily target. Do NOT call entryVeto to name the rail precisely — it
    // carries risk_audit + ntfy side effects per trip.
    String summary =
        result.candidates() + " candidates, " + result.entries() + " entries, " + result.exits()
            + " exits, " + result.exitSkipped() + " exit-skipped ("
            + result.strategies() + " strategies)"
            + (probe.capBound()
                ? " — entry governor bound: " + probe.wouldEnter() + " would-enter, "
                    + probe.admitted() + " admitted, " + probe.capExceedance() + " dropped"
                : "");
    publishQuietly(
        doctrine.batchName(),
        !result.refusalReasons().isEmpty()
            ? new SwingBatchAlert(
                doctrine.batchName(),
                doctrine.alertLabel() + " batch REFUSED",
                summary + " — " + String.join(", ", result.refusalReasons()))
            : result.exitSkipped() > 0
            ? new SwingBatchAlert(
                doctrine.batchName(),
                doctrine.alertLabel() + ": " + result.exitSkipped() + " exit(s) NOT evaluated",
                summary + " — see the STOP NOT EVALUATED TODAY errors in the service log.")
            // ⚠️ "batch done" only when the batch actually did BOTH passes. An entries-disabled run
            // completed its EXITS and nothing more — the 16:00 settle, or a catch-up whose funnel is
            // not the session's screen. Announcing that as done contradicted the coordinator, which
            // moments later publishes EXITS ONLY and leaves the session retryable precisely because
            // it is not done. Two alerts, opposite claims, same run; the operator believes the first.
            // Cross-vendor review Major, invisible to the coordinator's unit tests because they mock
            // this recorder.
            : new SwingBatchAlert(
                doctrine.batchName(),
                doctrine.alertLabel() + (entriesEnabled ? " batch done" : " exits pass complete"),
                summary));
    return new RunOutcome(result, markerRecorded);
  }

  /**
   * The scheduler path: run + record, but turn a thrown batch into a FAILED ops alert (the scheduler
   * must not die as a lone log line — a missed exit pass fires the pinned stops days late at worse
   * prices, corrupting the forward-paper evidence).
   */
  public SwingBatchEngine.SwingRun runScheduled(SwingDoctrine doctrine) {
    return runScheduled(doctrine, Pass.ENTRIES);
  }

  /**
   * The scheduler path with an explicitly DECLARED pass — the evening settle declares
   * {@link Pass#SETTLE}, the catch-up's own recovery never comes through here.
   *
   * <p>Added rather than reusing one of the snapshot-carrying {@link #runAndRecord} overloads
   * because those hardcode {@code executionArmed = true} for the catch-up's benefit (a historical
   * session's arming flag may legitimately have changed since). Routing the live scheduler through
   * one of them would let a DISABLED strategy exit and stamp completion — a Critical raised in
   * cross-vendor review of the first cut of this change, before merge. This overload delegates to
   * the four-argument form, which gates on {@code doctrine.enabled()}, and keeps the FAILED-alert
   * envelope that a direct {@code runAndRecord} call would have silently dropped.
   */
  public SwingBatchEngine.SwingRun runScheduled(SwingDoctrine doctrine, Pass pass) {
    try {
      SwingBatchEngine.SwingRun result =
          runAndRecord(
                  doctrine,
                  // A MANUAL run on a holiday pins TODAY rather than reaching back: the marker then
                  // lands on the day it ran, which is what keeps it from overwriting a real session.
                  scheduledSettleSession().orElseGet(() -> LocalDate.now(clock.withZone(IST))),
                  pass,
                  MarkerPolicy.ALWAYS)
              .run();
      log.info(
          "{} swing batch done: {} strategies, {} candidates, {} entries, {} exits, {} exit-skipped",
          doctrine.batchName(), result.strategies(), result.candidates(), result.entries(),
          result.exits(), result.exitSkipped());
      return result;
    } catch (RuntimeException e) {
      log.error("{} swing batch failed: {}", doctrine.batchName(), e.getMessage(), e);
      // ⚠️ The remediation must NOT tell the operator to POST /run after an exits-only settle. That
      // endpoint runs the FULL entries-plus-exits path, and at 16:00 this session's screen has not
      // landed — the funnel silently serves the PREVIOUS session's names, so following the
      // instruction would enter off yesterday's screen. Exactly the behaviour the 16:00/08:35 split
      // exists to prevent. Cross-vendor review, 2026-08-10.
      String remediation =
          pass.takesEntries()
              ? " Re-run via POST /api/v1/signals/" + doctrine.batchName() + "-swing/run."
              : " Do NOT POST /run to recover this — that endpoint takes ENTRIES too, and this"
                  + " session's screen has not landed yet, so it would enter off the previous"
                  + " session's names. The 08:35 catch-up re-runs this session pinned to its own"
                  + " bar; if the stops cannot wait that long, square off by hand.";
      publishQuietly(
          doctrine.batchName(),
          new SwingBatchAlert(
              doctrine.batchName(),
              doctrine.alertLabel() + " batch FAILED",
              "The scheduled batch threw: " + e.getMessage()
                  + " — open positions' stops were NOT evaluated." + remediation));
      return new SwingBatchEngine.SwingRun(0, 0, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty());
    }
  }

  private void publishQuietly(String batch, SwingBatchAlert alert) {
    try {
      events.publishEvent(alert);
    } catch (RuntimeException e) {
      log.warn("{} swing alert publish failed: {}", batch, e.getMessage());
    }
  }
}

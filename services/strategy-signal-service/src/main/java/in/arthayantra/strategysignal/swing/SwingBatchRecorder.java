package in.arthayantra.strategysignal.swing;

import in.arthayantra.strategysignal.signals.FlagSnapshotService;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
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

  /**
   * When the {@code swing_batch_runs} completeness marker is stamped. The scheduled / on-demand path
   * ALWAYS records (its historical behaviour). The catch-up records ON_COMPLETE — only when the exit
   * pass fully evaluated ({@code exitSkipped == 0}) — so a partial catch-up (a held anchor's bar was
   * missing) does NOT mark the session done and stays retryable (2026-07-17 review, Critical 1).
   */
  public enum MarkerPolicy {
    ALWAYS,
    ON_COMPLETE
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

  /** Runs one daily batch for a family, records the marker, and pushes the summary. Propagates. */
  public SwingBatchEngine.SwingRun runAndRecord(SwingDoctrine doctrine) {
    return runAndRecord(doctrine, null, true, MarkerPolicy.ALWAYS).run();
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
      SwingDoctrine doctrine, LocalDate sessionDate, boolean entriesEnabled, MarkerPolicy markerPolicy) {
    ReentrantLock lock = mutex.lockFor(doctrine.batchName());
    lock.lock();
    try {
      if (!doctrine.enabled()) {
        return runLocked(
            doctrine, sessionDate, entriesEnabled, markerPolicy, null, false, null);
      }
      SwingDoctrine.CandidateSnapshotRead read = doctrine.candidateSnapshot();
      return runLocked(
          doctrine,
          sessionDate,
          entriesEnabled,
          markerPolicy,
          read == null ? null : read.snapshot(),
          doctrine.enabled(), null);
    } finally {
      lock.unlock();
    }
  }

  /** Runs from the exact funnel snapshot already read by the catch-up readiness gate. */
  public RunOutcome runAndRecord(
      SwingDoctrine doctrine,
      LocalDate sessionDate,
      boolean entriesEnabled,
      MarkerPolicy markerPolicy,
      Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot) {
    ReentrantLock lock = mutex.lockFor(doctrine.batchName());
    lock.lock();
    try {
      // This overload is used by catch-up after it has read the historical schedule intent. The
      // current doctrine flag may have changed since that session, so it is deliberately not the gate.
      return runLocked(doctrine, sessionDate, entriesEnabled, markerPolicy, candidateSnapshot, true, null);
    } finally {
      lock.unlock();
    }
  }

  /** Runs from the catch-up snapshot with a deadline checked before the marker is written. */
  public RunOutcome runAndRecord(
      SwingDoctrine doctrine,
      LocalDate sessionDate,
      boolean entriesEnabled,
      MarkerPolicy markerPolicy,
      Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot,
      SwingBatchEngine.RunDeadline deadline) {
    ReentrantLock lock = mutex.lockFor(doctrine.batchName());
    lock.lock();
    try {
      return runLocked(
          doctrine, sessionDate, entriesEnabled, markerPolicy, candidateSnapshot, true, deadline);
    } finally {
      lock.unlock();
    }
  }

  private RunOutcome runLocked(
      SwingDoctrine doctrine,
      LocalDate sessionDate,
      boolean entriesEnabled,
      MarkerPolicy markerPolicy,
      Optional<SwingDoctrine.CandidateSnapshot> candidateSnapshot,
      boolean executionArmed,
      SwingBatchEngine.RunDeadline deadline) {
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
    if (snapshotAvailable
        && !result.deadlineReached()
        && result.refusalReasons().isEmpty()
        && (markerPolicy == MarkerPolicy.ALWAYS || result.exitSkipped() == 0)) {
      try {
        markerRecorded =
            runs.record(
                doctrine.batchName(), runDate, result.strategies(), result.candidates(),
                result.entries(), result.exits(), result.exitSkipped(), probe.openAtStart(),
                probe.wouldEnter(), probe.admitted(), probe.capExceedance(), probe.capBound(),
                probe.droppedByCap());
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
            : new SwingBatchAlert(doctrine.batchName(), doctrine.alertLabel() + " batch done", summary));
    return new RunOutcome(result, markerRecorded);
  }

  /**
   * The scheduler path: run + record, but turn a thrown batch into a FAILED ops alert (the scheduler
   * must not die as a lone log line — a missed exit pass fires the pinned stops days late at worse
   * prices, corrupting the forward-paper evidence).
   */
  public SwingBatchEngine.SwingRun runScheduled(SwingDoctrine doctrine) {
    try {
      SwingBatchEngine.SwingRun result = runAndRecord(doctrine);
      log.info(
          "{} swing batch done: {} strategies, {} candidates, {} entries, {} exits, {} exit-skipped",
          doctrine.batchName(), result.strategies(), result.candidates(), result.entries(),
          result.exits(), result.exitSkipped());
      return result;
    } catch (RuntimeException e) {
      log.error("{} swing batch failed: {}", doctrine.batchName(), e.getMessage(), e);
      publishQuietly(
          doctrine.batchName(),
          new SwingBatchAlert(
              doctrine.batchName(),
              doctrine.alertLabel() + " batch FAILED",
              "The scheduled batch threw: " + e.getMessage()
                  + " — open positions' stops were NOT evaluated. Re-run via POST"
                  + " /api/v1/signals/" + doctrine.batchName() + "-swing/run."));
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

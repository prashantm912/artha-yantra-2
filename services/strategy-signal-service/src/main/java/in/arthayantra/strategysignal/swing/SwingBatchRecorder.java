package in.arthayantra.strategysignal.swing;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

  private final SwingBatchEngine engine;
  private final SwingBatchRunRepository runs;
  private final SwingSellDecisionService sellDecisions;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  /** Wires the shared engine, the marker repo, the sell-decision store, and the event bus. */
  public SwingBatchRecorder(
      SwingBatchEngine engine,
      SwingBatchRunRepository runs,
      SwingSellDecisionService sellDecisions,
      ApplicationEventPublisher events,
      Clock clock) {
    this.engine = engine;
    this.runs = runs;
    this.sellDecisions = sellDecisions;
    this.events = events;
    this.clock = clock;
  }

  /** Runs one daily batch for a family, records the marker, and pushes the summary. Propagates. */
  public SwingBatchEngine.SwingRun runAndRecord(SwingDoctrine doctrine) {
    LocalDate runDate = LocalDate.now(clock.withZone(IST));
    SwingBatchEngine.SwingRun result = engine.runDaily(doctrine);
    if (!doctrine.enabled()) {
      return result; // disarmed no-op — nothing ran, nothing to record or announce
    }
    try {
      SwingBatchEngine.AdmissionProbe probe = result.admission();
      runs.record(
          doctrine.batchName(), runDate, result.strategies(), result.candidates(), result.entries(),
          result.exits(), result.exitSkipped(), probe.openAtStart(), probe.wouldEnter(),
          probe.admitted(), probe.capExceedance(), probe.capBound(), probe.droppedByCap());
    } catch (RuntimeException e) {
      log.warn("{} swing run-marker record failed: {}", doctrine.batchName(), e.getMessage());
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
    String summary =
        result.candidates() + " candidates, " + result.entries() + " entries, " + result.exits()
            + " exits, " + result.exitSkipped() + " exit-skipped ("
            + result.strategies() + " strategies)";
    publishQuietly(
        doctrine.batchName(),
        result.exitSkipped() > 0
            ? new SwingBatchAlert(
                doctrine.batchName(),
                doctrine.alertLabel() + ": " + result.exitSkipped() + " exit(s) NOT evaluated",
                summary + " — see the STOP NOT EVALUATED TODAY errors in the service log.")
            : new SwingBatchAlert(doctrine.batchName(), doctrine.alertLabel() + " batch done", summary));
    return result;
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

package in.arthayantra.strategysignal.minervini;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The ONE run path for the Minervini swing batch (audit P0-4 review): engine → marker → summary.
 * Both the 20:00 scheduler AND the on-demand {@code POST /run} go through here, so a manual
 * catch-up run records its {@code swing_batch_runs} marker (else the canary keeps alerting for a
 * date the owner already ran) and pushes the same summary. The run date is captured BEFORE the
 * engine runs (a past-IST-midnight completion must not stamp the next day). Engine exceptions
 * propagate — the scheduler turns them into a FAILED alert, the controller into a 500. A flag-off
 * no-op run records/pushes nothing.
 */
@Component
public class MinerviniSwingRunRecorder {

  private static final Logger log = LoggerFactory.getLogger(MinerviniSwingRunRecorder.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String BATCH = "minervini";

  private final MinerviniSwingEngine engine;
  private final SwingBatchRunRepository runs;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final boolean enabled;

  /** Wires the engine, the marker repo, the event bus, and the arming flag. */
  public MinerviniSwingRunRecorder(
      MinerviniSwingEngine engine,
      SwingBatchRunRepository runs,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${artha.minervini.swing.enabled:false}") boolean enabled) {
    this.engine = engine;
    this.runs = runs;
    this.events = events;
    this.clock = clock;
    this.enabled = enabled;
  }

  /** Runs one daily batch, records the marker, and pushes the summary. */
  public MinerviniSwingEngine.SwingRun runAndRecord() {
    LocalDate runDate = LocalDate.now(clock.withZone(IST));
    MinerviniSwingEngine.SwingRun result = engine.runDaily();
    if (!enabled) {
      return result; // disarmed no-op — nothing ran, nothing to record or announce
    }
    try {
      runs.record(
          BATCH, runDate, result.strategies(), result.candidates(), result.entries(),
          result.exits(), result.exitSkipped());
    } catch (RuntimeException e) {
      log.warn("minervini swing run-marker record failed: {}", e.getMessage());
    }
    String summary =
        result.candidates() + " candidates, " + result.entries() + " entries, " + result.exits()
            + " exits, " + result.exitSkipped() + " exit-skipped ("
            + result.strategies() + " strategies)";
    publishQuietly(
        result.exitSkipped() > 0
            ? new SwingBatchAlert(
                BATCH, "Minervini swing: " + result.exitSkipped() + " exit(s) NOT evaluated",
                summary + " — see the STOP NOT EVALUATED TODAY errors in the service log.")
            : new SwingBatchAlert(BATCH, "Minervini swing batch done", summary));
    return result;
  }

  private void publishQuietly(SwingBatchAlert alert) {
    try {
      events.publishEvent(alert);
    } catch (RuntimeException e) {
      log.warn("minervini swing alert publish failed: {}", e.getMessage());
    }
  }
}

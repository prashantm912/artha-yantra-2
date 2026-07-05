package in.arthayantra.strategysignal.manas;

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
 * The ONE run path for the Manas swing batch (mirror of {@code MinerviniSwingRunRecorder}):
 * engine → marker → summary, shared by the 20:05 scheduler and the on-demand {@code POST /run} —
 * a manual catch-up run records its marker (else the canary keeps alerting for a date the owner
 * already ran). Run date captured BEFORE the engine runs; engine exceptions propagate; a flag-off
 * no-op records/pushes nothing.
 */
@Component
public class ManasSwingRunRecorder {

  private static final Logger log = LoggerFactory.getLogger(ManasSwingRunRecorder.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String BATCH = "manas-arora";

  private final ManasAroraSwingEngine engine;
  private final SwingBatchRunRepository runs;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final boolean enabled;

  /** Wires the engine, the marker repo, the event bus, and the arming flag. */
  public ManasSwingRunRecorder(
      ManasAroraSwingEngine engine,
      SwingBatchRunRepository runs,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${artha.manas-arora.swing.enabled:false}") boolean enabled) {
    this.engine = engine;
    this.runs = runs;
    this.events = events;
    this.clock = clock;
    this.enabled = enabled;
  }

  /** Runs one daily batch, records the marker, and pushes the summary. */
  public ManasAroraSwingEngine.ManasSwingRun runAndRecord() {
    LocalDate runDate = LocalDate.now(clock.withZone(IST));
    ManasAroraSwingEngine.ManasSwingRun result = engine.runDaily();
    if (!enabled) {
      return result; // disarmed no-op — nothing ran, nothing to record or announce
    }
    try {
      runs.record(
          BATCH, runDate, result.strategies(), result.candidates(), result.entries(),
          result.exits(), result.exitSkipped());
    } catch (RuntimeException e) {
      log.warn("manas swing run-marker record failed: {}", e.getMessage());
    }
    String summary =
        result.candidates() + " candidates, " + result.entries() + " entries, " + result.exits()
            + " exits, " + result.exitSkipped() + " exit-skipped ("
            + result.strategies() + " strategies)";
    publishQuietly(
        result.exitSkipped() > 0
            ? new SwingBatchAlert(
                BATCH, "Manas swing: " + result.exitSkipped() + " exit(s) NOT evaluated",
                summary + " — see the STOP NOT EVALUATED TODAY errors in the service log.")
            : new SwingBatchAlert(BATCH, "Manas swing batch done", summary));
    return result;
  }

  private void publishQuietly(SwingBatchAlert alert) {
    try {
      events.publishEvent(alert);
    } catch (RuntimeException e) {
      log.warn("manas swing alert publish failed: {}", e.getMessage());
    }
  }
}

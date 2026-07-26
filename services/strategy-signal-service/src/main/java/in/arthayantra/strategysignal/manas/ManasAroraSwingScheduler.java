package in.arthayantra.strategysignal.manas;

import in.arthayantra.strategysignal.swing.SwingBatchIntentRepository;
import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Manas Arora swing batch once per trading evening (20:05 IST, weekdays), just after the
 * Minervini batch. Execution stays inert when the family flag is false, while this scheduler records
 * the effective schedule-time arming intent for the missed-batch detector.
 *
 * <p>The inert-when-disarmed gate is {@code SwingBatchEngine.runDaily}'s own {@code
 * doctrine.enabled()} check, NOT the recorder's — {@code SwingBatchRecorder.runAndRecord} calls the
 * engine before consulting its own flag. That one line is what makes it safe for this scheduler to
 * fire unconditionally so the intent row is always written.
 */
@Component
public class ManasAroraSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraSwingScheduler.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SwingBatchRecorder recorder;
  private final ManasDoctrine doctrine;
  private final SwingBatchIntentRepository intents;
  private final Clock clock;

  /** Wires the recorder, doctrine, schedule-intent ledger, and clock. */
  public ManasAroraSwingScheduler(
      SwingBatchRecorder recorder,
      ManasDoctrine doctrine,
      SwingBatchIntentRepository intents,
      Clock clock) {
    this.recorder = recorder;
    this.doctrine = doctrine;
    this.intents = intents;
    this.clock = clock;
  }

  /** Post-close daily run (20:05 IST, weekdays). */
  @Scheduled(cron = "${artha.manas-arora.swing.cron:0 5 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    LocalDate session = LocalDate.now(clock.withZone(IST));
    try {
      intents.recordScheduled(doctrine.batchName(), session, doctrine.enabled());
    } catch (RuntimeException e) {
      log.warn(
          "{} swing schedule-intent record failed for {} - continuing scheduled batch: {}",
          doctrine.batchName(),
          session,
          e.getMessage());
    }
    recorder.runScheduled(doctrine);
  }
}

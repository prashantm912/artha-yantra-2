package in.arthayantra.strategysignal.manas;

import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import in.arthayantra.strategysignal.swing.SwingBatchIntentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Manas Arora swing batch once per trading evening (20:05 IST, weekdays), just after the
 * Minervini batch (20:00) so the two don't contend. A thin per-family shell over the shared {@link
 * SwingBatchRecorder} (which owns the marker + the FAILED-alert envelope), bound to the {@link
 * ManasDoctrine}. The recorder keeps execution inert when {@code artha.manas-arora.swing.enabled} is
 * false (default off), while this scheduler still records the effective schedule-time arming intent.
 */
@Component
public class ManasAroraSwingScheduler {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SwingBatchRecorder recorder;
  private final ManasDoctrine doctrine;
  private final SwingBatchIntentRepository intents;
  private final Clock clock;

  /** Wires the shared recorder, Manas doctrine, schedule-intent ledger, and clock. */
  public ManasAroraSwingScheduler(
      SwingBatchRecorder recorder, ManasDoctrine doctrine, SwingBatchIntentRepository intents,
      Clock clock) {
    this.recorder = recorder;
    this.doctrine = doctrine;
    this.intents = intents;
    this.clock = clock;
  }

  /** Post-close daily run (20:05 IST, weekdays) — after the geometry/funnel refresh. */
  @Scheduled(cron = "${artha.manas-arora.swing.cron:0 5 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    LocalDate session = LocalDate.now(clock.withZone(IST));
    intents.recordScheduled(doctrine.batchName(), session, doctrine.enabled());
    recorder.runScheduled(doctrine);
  }
}

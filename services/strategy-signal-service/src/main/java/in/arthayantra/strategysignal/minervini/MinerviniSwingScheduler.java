package in.arthayantra.strategysignal.minervini;

import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import in.arthayantra.strategysignal.swing.SwingBatchIntentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Minervini swing batch once per trading evening (20:00 IST, weekdays), AFTER the
 * market-data geometry scan has refreshed the SEPA funnel (~19:30 IST). A thin per-family shell over
 * the shared {@link SwingBatchRecorder} (which owns the marker + the FAILED-alert envelope), bound to
 * the {@link MinerviniDoctrine}. The recorder keeps execution inert when
 * {@code artha.minervini.swing.enabled} is false (default off), while this scheduler still records
 * the effective schedule-time arming intent.
 */
@Component
public class MinerviniSwingScheduler {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SwingBatchRecorder recorder;
  private final MinerviniDoctrine doctrine;
  private final SwingBatchIntentRepository intents;
  private final Clock clock;

  /** Wires the shared recorder, Minervini doctrine, schedule-intent ledger, and clock. */
  public MinerviniSwingScheduler(
      SwingBatchRecorder recorder, MinerviniDoctrine doctrine, SwingBatchIntentRepository intents,
      Clock clock) {
    this.recorder = recorder;
    this.doctrine = doctrine;
    this.intents = intents;
    this.clock = clock;
  }

  /** Post-close daily run (20:00 IST, weekdays) — after the 19:30 geometry/funnel refresh. */
  @Scheduled(cron = "${artha.minervini.swing.cron:0 0 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    LocalDate session = LocalDate.now(clock.withZone(IST));
    intents.recordScheduled(doctrine.batchName(), session, doctrine.enabled());
    recorder.runScheduled(doctrine);
  }
}

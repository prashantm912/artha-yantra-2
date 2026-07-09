package in.arthayantra.strategysignal.minervini;

import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Minervini swing batch once per trading evening (20:00 IST, weekdays), AFTER the
 * market-data geometry scan has refreshed the SEPA funnel (~19:30 IST). A thin per-family shell over
 * the shared {@link SwingBatchRecorder} (which owns the marker + the FAILED-alert envelope), bound to
 * the {@link MinerviniDoctrine}. Gated on {@code artha.minervini.swing.enabled=true} (default off) so
 * CI/test/mock contexts stay inert.
 */
@Component
@ConditionalOnProperty(value = "artha.minervini.swing.enabled", havingValue = "true")
public class MinerviniSwingScheduler {

  private final SwingBatchRecorder recorder;
  private final MinerviniDoctrine doctrine;

  /** Wires the shared recorder and the Minervini doctrine. */
  public MinerviniSwingScheduler(SwingBatchRecorder recorder, MinerviniDoctrine doctrine) {
    this.recorder = recorder;
    this.doctrine = doctrine;
  }

  /** Post-close daily run (20:00 IST, weekdays) — after the 19:30 geometry/funnel refresh. */
  @Scheduled(cron = "${artha.minervini.swing.cron:0 0 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    recorder.runScheduled(doctrine);
  }
}

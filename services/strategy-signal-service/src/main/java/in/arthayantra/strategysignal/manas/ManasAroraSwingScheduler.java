package in.arthayantra.strategysignal.manas;

import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Manas Arora swing batch once per trading evening (20:05 IST, weekdays), just after the
 * Minervini batch (20:00) so the two don't contend. A thin per-family shell over the shared {@link
 * SwingBatchRecorder} (which owns the marker + the FAILED-alert envelope), bound to the {@link
 * ManasDoctrine}. Gated on {@code artha.manas-arora.swing.enabled=true} (default off) so CI/test/mock
 * contexts stay inert.
 */
@Component
@ConditionalOnProperty(value = "artha.manas-arora.swing.enabled", havingValue = "true")
public class ManasAroraSwingScheduler {

  private final SwingBatchRecorder recorder;
  private final ManasDoctrine doctrine;

  /** Wires the shared recorder and the Manas doctrine. */
  public ManasAroraSwingScheduler(SwingBatchRecorder recorder, ManasDoctrine doctrine) {
    this.recorder = recorder;
    this.doctrine = doctrine;
  }

  /** Post-close daily run (20:05 IST, weekdays) — after the geometry/funnel refresh. */
  @Scheduled(cron = "${artha.manas-arora.swing.cron:0 5 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    recorder.runScheduled(doctrine);
  }
}

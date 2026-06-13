package in.arthayantra.backtest.dispatch;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 28 placeholder "replay": advances progress 0→100 in a few steps so the full D12 lifecycle
 * (queued → running → progress → completed, plus cancel at a checkpoint) is observable end-to-end
 * over REST + WS. The real bar-by-bar replay through the {@code strategy-engine} JAR replaces this
 * in Phase 30; the worker/stream/jobs machinery around it does not change.
 */
@Component
public class ReplayStub {

  private final long stepMillis;

  /** Step delay keeps progress observable; tests shorten it. */
  public ReplayStub(@Value("${artha.backtest.stub-step-millis:40}") long stepMillis) {
    this.stepMillis = stepMillis;
  }

  /**
   * Runs the stub work, publishing progress and honoring cancellation at each checkpoint.
   *
   * @throws JobCancelledException if {@code cancelled} reports true at a checkpoint
   */
  public void run(UUID jobId, IntConsumer progress, BooleanSupplier cancelled) {
    for (int pct = 20; pct <= 100; pct += 20) {
      if (cancelled.getAsBoolean()) {
        throw new JobCancelledException(jobId);
      }
      sleep();
      progress.accept(pct);
    }
  }

  private void sleep() {
    if (stepMillis <= 0) {
      return;
    }
    try {
      Thread.sleep(stepMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JobCancelledException(null);
    }
  }
}

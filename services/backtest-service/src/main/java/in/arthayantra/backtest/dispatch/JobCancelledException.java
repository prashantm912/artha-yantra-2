package in.arthayantra.backtest.dispatch;

import java.util.UUID;

/** Thrown by the replay when a cancellation flag is observed at a bar-batch checkpoint (§D.5). */
public class JobCancelledException extends RuntimeException {

  /** The cancelled job. */
  public JobCancelledException(UUID jobId) {
    super("job cancelled at checkpoint: " + jobId);
  }
}

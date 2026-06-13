package in.arthayantra.backtest.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stage-D's one scheduled job of its own (plan §6.5 / §D.2): a monthly prune of terminal {@code
 * jobs} rows older than 180 days. Runs/trades remain the research record and are kept indefinitely;
 * Phase 30 adds the {@code NOT EXISTS backtest_runs} guard to the repository query.
 */
@Component
public class JobPruner {

  private static final Logger log = LoggerFactory.getLogger(JobPruner.class);
  private static final int RETENTION_DAYS = 180;

  private final JobRepository repository;

  /** Wires the repository. */
  public JobPruner(JobRepository repository) {
    this.repository = repository;
  }

  /** 03:00 IST on the 1st of every month. */
  @Scheduled(cron = "${artha.backtest.prune-cron:0 0 3 1 * *}", zone = "Asia/Kolkata")
  public void prune() {
    int removed = repository.pruneStaleTerminal(RETENTION_DAYS);
    if (removed > 0) {
      log.info("pruned {} terminal jobs older than {} days", removed, RETENTION_DAYS);
    }
  }
}

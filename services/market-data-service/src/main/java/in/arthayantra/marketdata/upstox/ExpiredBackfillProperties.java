package in.arthayantra.marketdata.upstox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auto-resume config for the expired-instruments backfill ({@link ExpiredBackfillAutoResume}). The
 * backfill job is an in-memory daemon thread, so any restart kills it; these knobs let it re-drive
 * itself without a manual re-POST.
 *
 * @param autoResume master switch — fire the coverage-aware resume on boot + self-heal hourly
 *     (default true on the live profile; the mock profile has no Upstox client → no-op regardless).
 * @param selfHealCron Spring cron for the self-heal tick (only read by {@code @Scheduled}; this field
 *     mirrors it for completeness). Default hourly at second 0 / minute 17.
 * @param catchupHours once a run has finished cleanly with full coverage, the self-heal re-walks at
 *     most once per this window (to pick up newly-expired contracts) — not every tick.
 */
@ConfigurationProperties(prefix = "artha.marketdata.expired-backfill")
public record ExpiredBackfillProperties(boolean autoResume, String selfHealCron, int catchupHours) {

  /** Defaults for direct construction (tests) + a guard against a blank/zero override. */
  public ExpiredBackfillProperties {
    if (selfHealCron == null || selfHealCron.isBlank()) {
      selfHealCron = "0 17 * * * *";
    }
    if (catchupHours <= 0) {
      catchupHours = 20;
    }
  }
}

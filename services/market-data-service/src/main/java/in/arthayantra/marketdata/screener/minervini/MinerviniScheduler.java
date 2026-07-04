package in.arthayantra.marketdata.screener.minervini;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Minervini daily screen + persists it. Fires a boot one-shot (so a fresh stack has a
 * screen immediately) and a nightly cron AFTER the ~19:00 IST bhavcopy pull (so the day's daily
 * bars are present). Fail-soft: a screen failure is logged, never fatal (mirrors
 * {@code NseEodScheduler.pullBhavcopy}). Gated by {@code artha.minervini.screen.enabled}.
 */
@Component
public class MinerviniScheduler {

  private static final Logger log = LoggerFactory.getLogger(MinerviniScheduler.class);

  private final TrendTemplateService screener;
  private final MinerviniScreenRepository repo;
  private final boolean enabled;

  /** Wires the screener + repository. */
  public MinerviniScheduler(
      TrendTemplateService screener,
      MinerviniScreenRepository repo,
      @Value("${artha.minervini.screen.enabled:true}") boolean enabled) {
    this.screener = screener;
    this.repo = repo;
    this.enabled = enabled;
  }

  /** Boot one-shot. */
  @EventListener(ApplicationReadyEvent.class)
  void onStartup() {
    runQuietly("startup");
  }

  /** Nightly, after the bhavcopy pull. */
  @Scheduled(cron = "${artha.minervini.cron:0 30 19 * * MON-FRI}", zone = "Asia/Kolkata")
  void scheduled() {
    runQuietly("scheduled");
  }

  /** On-demand run (used by the controller's POST /run). Returns rows written. */
  public int runOnce(LocalDate asOf) {
    TrendTemplateService.ScreenResult r = screener.screen(asOf);
    if (r.screenDate() == null) {
      return 0;
    }
    return repo.upsertAll(r.screenDate(), r.candidates());
  }

  private void runQuietly(String trigger) {
    if (!enabled) {
      return;
    }
    try {
      TrendTemplateService.ScreenResult r = screener.screen(null);
      if (r.screenDate() == null) {
        log.info("minervini screen skipped ({}) — no daily equity data yet", trigger);
        return;
      }
      int written = repo.upsertAll(r.screenDate(), r.candidates());
      long passing = r.candidates().stream().filter(TrendCandidate::passesAll).count();
      log.info(
          "minervini screen upserted {} rows for {} ({} pass all 8 gates) [{}]",
          written, r.screenDate(), passing, trigger);
    } catch (Exception e) {
      log.warn("minervini screen failed ({}) — non-fatal", trigger, e);
    }
  }
}

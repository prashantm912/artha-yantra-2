package in.arthayantra.marketdata.nse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily NSE EOD pull (B-1b). FII/DII first; later sources (participant-OI, delivery) add their
 * fetch here. Pulls once on startup (immediate data + the live-fetch canary) and daily after close.
 * A fetch failure is logged, never fatal — NSE anti-bot/outage must not break the service.
 */
@Component
public class NseEodScheduler {

  private static final Logger log = LoggerFactory.getLogger(NseEodScheduler.class);

  private final FiiDiiFetcher fiiDii;
  private final NseEodFiiDiiRepository repo;

  public NseEodScheduler(FiiDiiFetcher fiiDii, NseEodFiiDiiRepository repo) {
    this.fiiDii = fiiDii;
    this.repo = repo;
  }

  /** Pull once on startup so data is present immediately and the NSE fetch path is exercised. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    pullFiiDii();
  }

  /** Daily after close (default 19:00 IST). */
  @Scheduled(cron = "${artha.nse.eod-cron:0 0 19 * * MON-FRI}", zone = "Asia/Kolkata")
  public void scheduledPull() {
    pullFiiDii();
  }

  private void pullFiiDii() {
    try {
      var rows = fiiDii.fetchLatest();
      repo.upsertAll(rows);
      log.info("NSE FII/DII EOD upserted {} rows", rows.size());
    } catch (RuntimeException failed) {
      log.warn("NSE FII/DII EOD pull failed (will retry next schedule): {}", failed.getMessage());
    }
  }
}

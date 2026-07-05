package in.arthayantra.marketdata.screener.manas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Manas Arora daily selection screen + persists it. Fires a boot one-shot (so a fresh stack
 * has a screen immediately) and a nightly cron AFTER the ~19:00 IST bhavcopy pull (so the day's daily
 * bars are present). Fail-soft: a screen failure is logged, never fatal. Gated DEFAULT-OFF by
 * {@code artha.manas-arora.screen.enabled} — the whole component is created ONLY when the flag is
 * true, so nothing runs until a deploy wires it (this differs from the always-on Minervini scheduler,
 * which guards inside its method; Manas defaults off/absent as a new, deploy-gated screener). The
 * on-demand {@code POST /run} path drives {@code ManasScreenService}/{@code ManasGeometryService}
 * directly and works regardless of this flag.
 */
@Component
@ConditionalOnProperty(prefix = "artha.manas-arora.screen", name = "enabled", havingValue = "true")
public class ManasScheduler {

  private static final Logger log = LoggerFactory.getLogger(ManasScheduler.class);

  private final ManasScreenService screener;
  private final ManasScreenRepository repo;
  private final ManasGeometryService geometry;

  /** Wires the screener + screen repository + geometry service. */
  public ManasScheduler(
      ManasScreenService screener, ManasScreenRepository repo, ManasGeometryService geometry) {
    this.screener = screener;
    this.repo = repo;
    this.geometry = geometry;
  }

  /** Boot one-shot. */
  @EventListener(ApplicationReadyEvent.class)
  void onStartup() {
    runQuietly("startup");
  }

  /** Nightly, after the bhavcopy pull. */
  @Scheduled(cron = "${artha.manas-arora.cron:0 40 19 * * MON-FRI}", zone = "Asia/Kolkata")
  void scheduled() {
    runQuietly("scheduled");
  }

  private void runQuietly(String trigger) {
    try {
      ManasScreenService.ScreenResult r = screener.screen(null);
      if (r.screenDate() == null) {
        log.info("manas screen skipped ({}) — no daily equity data yet", trigger);
        return;
      }
      int written = repo.upsertAll(r.screenDate(), r.candidates());
      long passing = r.candidates().stream().filter(ManasCandidate::passesAll).count();
      int geo = computeGeometry(r);
      log.info(
          "manas screen upserted {} rows for {} ({} pass all gates, {} geometry rows) [{}]",
          written, r.screenDate(), passing, geo, trigger);
    } catch (Exception e) {
      log.warn("manas screen failed ({}) — non-fatal", trigger, e);
    }
  }

  /**
   * Computes + persists vcp/breakout geometry for the day's passers (delegates to the geometry
   * service, the single {@code manas_arora_setups} writer). Fail-soft — a geometry failure never
   * fails the screen.
   */
  private int computeGeometry(ManasScreenService.ScreenResult r) {
    try {
      return geometry.persistForPassers(r.screenDate(), r.candidates());
    } catch (Exception e) {
      log.warn("manas geometry failed for {} — non-fatal", r.screenDate(), e);
      return 0;
    }
  }
}

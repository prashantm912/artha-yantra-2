package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillCompleted;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Minervini daily screen + persists it. The PRIMARY trigger is the
 * {@link BhavcopyBackfillCompleted} event — the screen keys on {@code max(trade_date)} of
 * {@code nse_eod_bhavcopy}, so it must run AFTER the day's bhavcopy rows land (the old identical
 * 19:30 cron raced the backfill and screened yesterday; audit H1 2026-07-05). A boot one-shot (so
 * a fresh stack has a screen immediately) and a 19:50 IST fallback cron (in case the backfill
 * fails and never publishes) remain — all three paths are idempotent upserts. Fail-soft: a screen
 * failure is logged, never fatal. Gated by {@code artha.minervini.screen.enabled}.
 */
@Component
public class MinerviniScheduler {

  private static final Logger log = LoggerFactory.getLogger(MinerviniScheduler.class);

  private final TrendTemplateService screener;
  private final MinerviniScreenRepository repo;
  private final MinerviniGeometryService geometry;
  private final boolean enabled;

  /** Wires the screener + screen repository + geometry service. */
  public MinerviniScheduler(
      TrendTemplateService screener,
      MinerviniScreenRepository repo,
      MinerviniGeometryService geometry,
      @Value("${artha.minervini.screen.enabled:true}") boolean enabled) {
    this.screener = screener;
    this.repo = repo;
    this.geometry = geometry;
    this.enabled = enabled;
  }

  /** Boot one-shot. */
  @EventListener(ApplicationReadyEvent.class)
  void onStartup() {
    runQuietly("startup");
  }

  /** Primary trigger: the day's bhavcopy rows just landed — screen against the fresh watermark. */
  @EventListener(BhavcopyBackfillCompleted.class)
  void onBhavcopyBackfillCompleted() {
    runQuietly("bhavcopy-complete");
  }

  /** Fallback cron (bhavcopy backfill failed → no event). 19:50, before the 20:00 swing batch. */
  @Scheduled(cron = "${artha.minervini.cron:0 50 19 * * MON-FRI}", zone = "Asia/Kolkata")
  void scheduled() {
    runQuietly("scheduled");
  }

  /** On-demand run (used by the controller's POST /run). Returns rows written. */
  public int runOnce(LocalDate asOf) {
    TrendTemplateService.ScreenResult r = screener.screen(asOf);
    if (r.screenDate() == null) {
      return 0;
    }
    int written = repo.upsertAll(r.screenDate(), r.candidates());
    computeGeometry(r);
    return written;
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
      int geo = computeGeometry(r);
      log.info(
          "minervini screen upserted {} rows for {} ({} pass all 8 gates, {} geometry rows) [{}]",
          written, r.screenDate(), passing, geo, trigger);
    } catch (Exception e) {
      log.warn("minervini screen failed ({}) — non-fatal", trigger, e);
    }
  }

  /**
   * Computes + persists VCP/base geometry for the day's passers (delegates to the geometry service,
   * the single {@code minervini_setups} writer). Fail-soft — a geometry failure never fails the
   * screen.
   */
  private int computeGeometry(TrendTemplateService.ScreenResult r) {
    try {
      return geometry.persistForPassers(r.screenDate(), r.candidates());
    } catch (Exception e) {
      log.warn("minervini geometry failed for {} — non-fatal", r.screenDate(), e);
      return 0;
    }
  }
}

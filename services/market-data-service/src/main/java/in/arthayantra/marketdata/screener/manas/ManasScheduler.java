package in.arthayantra.marketdata.screener.manas;

import in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillCompleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Manas Arora daily selection screen + persists it. The PRIMARY trigger is the
 * {@link BhavcopyBackfillCompleted} event — the screen keys on the bhavcopy watermark, so it must
 * run AFTER the day's rows land (the old 19:40 cron had only ~10 min of accidental headroom over
 * the 19:30 backfill; audit H1 2026-07-05). A boot one-shot and a 19:55 IST fallback cron (in case
 * the backfill fails and never publishes) remain — all three paths are idempotent upserts.
 * Fail-soft: a screen failure is logged, never fatal. Gated DEFAULT-OFF by
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
  private final in.arthayantra.marketdata.alerts.NtfyClient ntfy;

  /** Wires the screener + screen repository + geometry service + the ops ntfy client. */
  public ManasScheduler(
      ManasScreenService screener,
      ManasScreenRepository repo,
      ManasGeometryService geometry,
      in.arthayantra.marketdata.alerts.NtfyClient ntfy) {
    this.screener = screener;
    this.repo = repo;
    this.geometry = geometry;
    this.ntfy = ntfy;
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

  /** Fallback cron (bhavcopy backfill failed → no event). 19:55, before the 20:05 swing batch. */
  @Scheduled(cron = "${artha.manas-arora.cron:0 55 19 * * MON-FRI}", zone = "Asia/Kolkata")
  void scheduled() {
    runQuietly("scheduled");
  }

  private void runQuietly(String trigger) {
    try {
      // Already screened the current bhavcopy watermark? Skip (mirror of the Minervini scheduler):
      // the fallback cron / boot one-shot / holiday no-op event become cheap no-ops instead of a
      // second full screen + geometry fan-out. POST /run stays the forced-recompute path.
      java.time.LocalDate persisted = repo.latestScreenDate();
      if (persisted != null && persisted.equals(screener.latestScreenDate())) {
        log.debug("manas screen already current for {} — skipped ({})", persisted, trigger);
        return;
      }
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
      // Audit P0-4/H10: a failed screen leaves the 20:05 swing batch on yesterday's funnel — the
      // owner must hear about it, not find it in a log next week. NtfyClient never throws.
      log.warn("manas screen failed ({}) — non-fatal", trigger, e);
      ntfy.send(
          "Manas screen FAILED", "high",
          "Trigger " + trigger + ": " + e.getMessage()
              + " — the 20:05 swing batch will read a stale funnel.");
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

package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillCompleted;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
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
  private final PlaneDivergenceProbe planeDivergence;
  private final in.arthayantra.marketdata.alerts.NtfyClient ntfy;
  private final IngestRunLedger ledger;
  private final boolean enabled;
  private final boolean planeDivergenceEnabled;

  /** Wires the screener + screen repository + geometry service + the ops ntfy client + ingest ledger. */
  public MinerviniScheduler(
      TrendTemplateService screener,
      MinerviniScreenRepository repo,
      MinerviniGeometryService geometry,
      PlaneDivergenceProbe planeDivergence,
      in.arthayantra.marketdata.alerts.NtfyClient ntfy,
      IngestRunLedger ledger,
      @Value("${artha.minervini.screen.enabled:true}") boolean enabled,
      @Value("${artha.minervini.plane-divergence.enabled:true}") boolean planeDivergenceEnabled) {
    this.screener = screener;
    this.repo = repo;
    this.geometry = geometry;
    this.planeDivergence = planeDivergence;
    this.ntfy = ntfy;
    this.ledger = ledger;
    this.enabled = enabled;
    this.planeDivergenceEnabled = planeDivergenceEnabled;
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
    // Ingest-run ledger (audit §7.2.3). Opened only after the dedup skip below, so a no-op run
    // records nothing; the id survives into the catch so a screen failure is recorded, not vanished.
    Long runId = null;
    try {
      // Already screened the current bhavcopy watermark? Skip — makes the fallback cron, the boot
      // one-shot on an up-to-date stack, and a holiday's no-op-backfill event all cheap no-ops
      // instead of a second full screen + ~210-symbol geometry fan-out on the shared Timescale box.
      // POST /run (runOnce) deliberately bypasses this — it is the forced-recompute path, and the
      // heal for the rare screen-persisted-but-geometry-failed evening.
      LocalDate persisted = repo.latestScreenDate();
      if (persisted != null && persisted.equals(screener.latestScreenDate())) {
        log.debug("minervini screen already current for {} — skipped ({})", persisted, trigger);
        return;
      }
      runId = ledger.start(IngestRunLedger.SOURCE_MINERVINI_SCREEN);
      TrendTemplateService.ScreenResult r = screener.screen(null);
      if (r.screenDate() == null) {
        ledger.succeed(runId, 0);
        log.info("minervini screen skipped ({}) — no daily equity data yet", trigger);
        return;
      }
      int written = repo.upsertAll(r.screenDate(), r.candidates());
      long passing = r.candidates().stream().filter(TrendCandidate::passesAll).count();
      int geo = computeGeometry(r);
      ledger.succeed(runId, written);
      log.info(
          "minervini screen upserted {} rows for {} ({} pass all 8 gates, {} geometry rows) [{}]",
          written, r.screenDate(), passing, geo, trigger);
      probePlaneDivergence(r.screenDate());
    } catch (Exception e) {
      // Audit P0-4/H10: a failed screen leaves the 20:00 swing batch on yesterday's funnel — the
      // owner must hear about it, not find it in a log next week. NtfyClient never throws.
      ledger.fail(runId, e.getMessage());
      log.warn("minervini screen failed ({}) — non-fatal", trigger, e);
      ntfy.send(
          "Minervini screen FAILED", "high",
          "Trigger " + trigger + ": " + e.getMessage()
              + " — the 20:00 swing batch will read a stale funnel.");
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

  /**
   * Two-plane read-back over the screen just persisted (see {@link PlaneDivergenceProbe}). Runs here
   * rather than on its own cron because this is the ONLY moment the screen + geometry are both fresh
   * and the funnel is fully formed — a separate job would have to re-derive when that is true.
   *
   * <p>Always logs; pages ONLY when a divergent symbol is a SERVED candidate, because a divergence
   * that never reaches the funnel cannot move money. Fail-soft: the probe never fails a screen.
   */
  private void probePlaneDivergence(LocalDate screenDate) {
    if (!planeDivergenceEnabled) {
      return;
    }
    try {
      PlaneDivergenceProbe.Report r = planeDivergence.probe(screenDate);
      if (r.divergentPassers() == 0) {
        log.info("minervini plane-divergence {}: none of {} passers diverge", screenDate,
            r.passersChecked());
        return;
      }
      log.info(
          "minervini plane-divergence {}: {} of {} passers read two ways (>= {}% over {}d), {} are"
              + " served candidates, {} clear the {}% page floor — {}",
          screenDate, r.divergentPassers(), r.passersChecked(), r.thresholdPct(), r.lookbackDays(),
          r.divergentCandidates(), r.alertingCandidates(), r.alertPct(), r.names());
      if (r.alertingCandidates() > 0) {
        String who =
            r.names().stream()
                .filter(r::isAlerting)
                .map(n -> n.symbol() + " " + n.maxDivergencePct() + "% (worst " + n.worstBar() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        ntfy.send(
            "Minervini funnel candidate read off two price planes",
            "high",
            screenDate
                + ": "
                + who
                + " — candles@1d is dividend-back-adjusted, nse_eod_bhavcopy is not. The screen"
                + " admitted a name the engine prices differently; neither plane was changed.");
      }
    } catch (Exception e) {
      log.warn("minervini plane-divergence probe failed for {} — non-fatal", screenDate, e);
    }
  }
}

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

  /**
   * On-demand forced recompute — the ONE orchestration behind {@code POST /run}.
   *
   * <p>⚠️ This method's javadoc used to claim the controller called it while the controller in fact
   * duplicated the screen/upsert/geometry sequence inline, so {@code runOnce} had no production
   * caller at all. Every guarantee added here — geometry consistency, and now the plane-divergence
   * observation — silently did not apply to the one path a human triggers by hand. The controller
   * now delegates; keep it that way, and {@link MinerviniRunEndpointTest} fails if it stops.
   *
   * <p>The probe is FORCED here. A recompute rewrites {@code computed_at} and can change the
   * candidate set, so the observation the date already carries describes a screen that no longer
   * exists — letting the existing completion marker suppress a fresh reading would make the
   * durability marker actively destroy the thing it exists to protect.
   *
   * <p>Returns the fresh {@link TrendTemplateService.ScreenResult} so the caller can render exactly
   * what was persisted without a second read.
   */
  public TrendTemplateService.ScreenResult runOnce(LocalDate asOf) {
    TrendTemplateService.ScreenResult r = screener.screen(asOf);
    if (r.screenDate() == null) {
      return r;
    }
    repo.upsertAll(r.screenDate(), r.candidates());
    computeGeometry(r);
    probePlaneDivergence(r.screenDate(), "run-once", true);
    return r;
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
        // ...but the probe is a SEPARATE observation with its own durable marker. A crash or a
        // probe exception after the screen persisted used to be unrecoverable: every later door
        // hit this skip and returned, so that evening's plane-divergence reading was lost for
        // good. Retry it here instead — the screen stays skipped, only the probe re-runs.
        probePlaneDivergence(persisted, trigger + "-retry", false);
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
      probePlaneDivergence(r.screenDate(), trigger, false);
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
   * Two-plane read-back over the screen just persisted (see {@link PlaneDivergenceProbe}). Runs
   * here rather than on its own cron because this is the ONLY moment the screen, the geometry and
   * therefore the funnel are all fresh for the date; a separate job would have to re-derive when
   * that is true.
   *
   * <p><b>Durable, and retried.</b> Completion is recorded per screen date by the probe itself, and
   * every door into the scheduler retries a date that has no completion row — including the
   * dedup-skip path above. Without that, the ordering was silently lossy: the screen persists and
   * the ingest ledger succeeds BEFORE the probe runs, so a crash or a probe exception in between
   * left the screen "already current" and every later trigger skipped straight past the missing
   * observation, permanently.
   *
   * <p>Unlike {@code IngestCoverageCanary} this needs no two-state claim or lease. That protocol
   * exists because a duplicate PAGE is harmful; this probe writes only a log line and a marker row,
   * so a duplicate run is harmless and a single completion marker is the whole requirement. If this
   * ever grows an alert, it must adopt the CLAIMED→DONE protocol with it.
   *
   * <p>{@code force} is for the recompute path only — see {@link #runOnce}. Every scheduled door
   * passes false, so a completed date is observed exactly once.
   *
   * <p>Fail-soft: a probe failure never fails a screen, and it leaves the date UNMARKED so the next
   * door retries rather than inheriting the gap.
   */
  private void probePlaneDivergence(LocalDate screenDate, String trigger, boolean force) {
    if (!planeDivergenceEnabled || screenDate == null) {
      return;
    }
    try {
      if (!force && planeDivergence.alreadyReported(screenDate)) {
        return;
      }
      PlaneDivergenceProbe.Report r = planeDivergence.probe(screenDate);
      log.info(
          "minervini plane-divergence {} [{}]: {} of {} passers read two ways (>= {}% over {}d),"
              + " {} are served candidates; {} bar-pairs compared, {} excluded as-of (cutoff {}),"
              + " {} symbols unjudgeable — {}",
          screenDate, trigger, r.divergentPassers(), r.passersChecked(), r.thresholdPct(),
          r.lookbackDays(), r.divergentCandidates(), r.barsCompared(), r.barsExcludedAsOf(),
          r.asOfCutoff(), r.symbolsWithNoHonestBars(), r.names());
      planeDivergence.markReported(screenDate);
    } catch (Exception e) {
      log.warn(
          "minervini plane-divergence probe failed for {} — non-fatal, will retry", screenDate, e);
    }
  }
}

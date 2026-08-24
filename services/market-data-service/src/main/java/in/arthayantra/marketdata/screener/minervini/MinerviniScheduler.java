package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillCompleted;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import java.time.LocalDate;
import java.util.concurrent.locks.ReentrantLock;
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

  /**
   * Serialises the three screen doors against each other (ledger H13).
   *
   * <p>⚠️ FOUR doors, not three: the two {@code @EventListener}s, the {@code @Scheduled} cron, and
   * {@code runOnce} behind {@code POST /run}. The lock covers all four.
   *
   * <p>⚠️ The dedup below is a READ-then-ACT on {@code latestScreenDate}, and nothing made it
   * atomic. Measured live on 2026-08-24: the {@code bhavcopy-complete} event ran 18:46:42→18:47:39
   * on thread {@code eod-bhavcopy-backfill} while the 18:47 cron started at 18:46:57 on
   * {@code scheduling-1} — the cron read the watermark BEFORE the event run had written it, so the
   * skip did not fire and the day got two full screens plus two ~290-symbol geometry fan-outs
   * against a 1 GB Timescale box that has OOM-crashed twice. Both wrote the same 1,800 rows, so it
   * cost load rather than data, but two concurrent {@code replaceAll} on one screen date is a
   * delete/insert interleave waiting for a slower evening.
   *
   * <p>A JVM lock is sufficient BECAUSE all three doors are in-process (two {@code @EventListener},
   * one {@code @Scheduled}) and market-data runs one container — the same single-writer convention
   * the rest of the ingest path assumes. It is not a distributed claim and must not be read as one.
   */
  private final ReentrantLock screenLock = new ReentrantLock();

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
  @Scheduled(cron = "${artha.minervini.cron:0 47 18 * * MON-FRI}", zone = "Asia/Kolkata")
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
    // ⚠️ BLOCKING here, unlike the scheduled doors, and the asymmetry is the point: this runs on an
    // HTTP request thread where waiting is acceptable, and it deliberately bypasses the dedup skip
    // — so without the lock it is the ONE door that can still race a scheduled screen into two
    // concurrent replaceAll on the same date. Skipping would be wrong (a forced recompute that
    // silently did not recompute), so it waits instead.
    screenLock.lock();
    try {
      return runOnceLocked(asOf);
    } finally {
      screenLock.unlock();
    }
  }

  private TrendTemplateService.ScreenResult runOnceLocked(LocalDate asOf) {
    TrendTemplateService.ScreenResult r = screener.screen(asOf);
    if (r.screenDate() == null) {
      return r;
    }
    repo.replaceAll(r.screenDate(), r.candidates());
    computeGeometry(r);
    probePlaneDivergence(r.screenDate(), "run-once", true);
    return r;
  }

  private void runQuietly(String trigger) {
    if (!enabled) {
      return;
    }
    // ⚠️ The wait is PER DOOR, and the first cut got this wrong by treating all three the same.
    //
    // `scheduled()` has no `scheduler = ...`, so it runs on the DEFAULT taskScheduler — pool size 1,
    // shared by ~32 scheduled methods. Blocking THAT for the ~60 s a screen takes would starve every
    // one of them, and skipping costs nothing because the in-flight run is doing its work anyway.
    //
    // `bhavcopy-complete` is different in both respects. It runs synchronously on the dedicated
    // `eod-bhavcopy-backfill` executor, whose own javadoc says nothing queues behind it (the next
    // cron is a day away), so blocking there is already blessed. And dropping it is NOT free: this
    // is the trigger that exists to screen the FRESH watermark, added because the old identical
    // cron raced the backfill and screened yesterday (audit H1). On 2026-08-24 the backfill
    // published at 18:46:42 — 102 s after its 18:45 start, against a 120 s gap to the 18:47 cron.
    // Eighteen seconds of margin. Any evening the NSE leg overruns that, the ORDER REVERSES: the
    // cron takes the lock first, screens the OLD watermark, writes a SUCCESS ingest_runs row, and
    // a bare tryLock would drop the one door that would have corrected it — deterministically,
    // where the pre-fix code at least raced its way to the fresh screen. So it waits.
    if (!acquire(trigger)) {
      // ⚠️ ERROR for the event door, WARN for the others, and the split is the point. A skipped
      // cron/boot door is the DESIGNED outcome — the in-flight run is doing its work. A
      // bhavcopy-complete door that waited three minutes and still could not get in means the screen
      // is wedged and tonight's fresh watermark may go unscreened, which is the audit-H1 regression
      // this trigger exists to prevent. Different severities because they are different events.
      if ("bhavcopy-complete".equals(trigger)) {
        log.error(
            "minervini screen still locked after {} ms — the bhavcopy-complete door gave up;"
                + " tonight's fresh watermark may go unscreened (H13)",
            EVENT_DOOR_WAIT_MS);
      } else {
        log.warn(
            "minervini screen already running — {} trigger skipped (H13: two doors overlapped)",
            trigger);
      }
      return;
    }
    try {
      runLocked(trigger);
    } finally {
      screenLock.unlock();
    }
  }

  /** Waits for the screen lock in the way this door can afford — see {@link #runQuietly}. */
  private boolean acquire(String trigger) {
    return screenLock.tryLock();
  }

  /** Long enough to outlast a slow backfill, short enough that a wedged screen cannot hold a boot. */
  private static final long EVENT_DOOR_WAIT_MS = 180_000;

  private void runLocked(String trigger) {
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
      int written = repo.replaceAll(r.screenDate(), r.candidates());
      long passing = r.candidates().stream().filter(TrendCandidate::passesAll).count();
      int geo = computeGeometry(r);
      ledger.succeed(runId, written);
      log.info(
          "minervini screen upserted {} rows for {} ({} pass all 8 gates, {} geometry rows) [{}]",
          written, r.screenDate(), passing, geo, trigger);
      probePlaneDivergence(r.screenDate(), trigger, false);
    } catch (Exception e) {
      // Audit P0-4/H10: a failed screen leaves the NEXT swing batch on yesterday's funnel — the
      // owner must hear about it, not find it in a log next week. NtfyClient never throws.
      ledger.fail(runId, e.getMessage());
      log.warn("minervini screen failed ({}) — non-fatal", trigger, e);
      ntfy.send(
          "Minervini screen FAILED", "high",
          "Trigger " + trigger + ": " + e.getMessage()
              + " — the next swing batch will read a stale funnel.");
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
   * dedup-skip path above. ⚠️ The H13 lock-contention return added 2026-08-25 is the ONE exception:
   * it leaves before the probe, deliberately, because the run currently holding the lock is already
   * probing that same date. Without that, the ordering was silently lossy: the screen persists and
   * the ingest ledger succeeds BEFORE the probe runs, so a crash or a probe exception in between
   * left the screen "already current" and every later trigger skipped straight past the missing
   * observation, permanently.
   *
   * <p>Unlike {@code IngestCoverageCanary} this needs no two-state claim or lease. That protocol
   * exists because a duplicate PAGE is harmful; this probe writes only a log line and a marker row,
   * so a duplicate run is harmless and a single completion marker is the whole requirement. If this
   * ever grows an alert, it must adopt the CLAIMED→DONE protocol with it.
   *
   * <p><b>Two independent guards, covering different things.</b> {@code
   * PlaneDivergenceProbe#alreadyReported} is <i>derived</i> — a marker counts only while it is at
   * least as new as the screen's {@code computed_at} — which re-opens the date automatically after
   * ANY recompute, including one whose probe then failed or was disabled. {@code force} is the
   * explicit operator guarantee for {@link #runOnce}, and it additionally covers the one case
   * derivation misses: a recompute that upserts ZERO rows leaves {@code computed_at} unmoved. Every
   * scheduled door passes false, so a date whose marker still describes the current screen is
   * observed exactly once.
   *
   * <p>Fail-soft: a probe failure never fails a screen, and it leaves the date UNMARKED so the next
   * door retries rather than inheriting the gap.
   */
  private void probePlaneDivergence(LocalDate screenDate, String trigger, boolean force) {
    if (!planeDivergenceEnabled || screenDate == null) {
      return;
    }
    try {
      // Fail-OPEN on the marker read: if canary_runs itself is unusable, observe anyway. Absence of
      // evidence must never buy silence, and a duplicate log line is the whole cost of being wrong.
      boolean done = false;
      if (!force) {
        try {
          done = planeDivergence.alreadyReported(screenDate);
        } catch (Exception e) {
          log.warn("minervini plane-divergence marker unreadable for {} — observing anyway",
              screenDate, e);
        }
      }
      if (done) {
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

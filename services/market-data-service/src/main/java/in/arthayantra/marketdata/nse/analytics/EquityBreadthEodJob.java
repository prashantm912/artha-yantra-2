package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.nse.NseEodBhavcopyRepository;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Materializes the daily market-breadth fold into {@code equity_breadth_daily} (audit §3.3 / §6.10).
 * Runs on a daily cron plus a boot one-shot. ⚠️ The cron is env-driven
 * ({@code ARTHA_BREADTH_MATERIALIZE_CRON}, {@code deploy/docker-compose.yml:420}) and is
 * <b>18:51 IST</b> on the live stack, NOT the 19:55 this javadoc claimed until H24 PR-6 — #1358
 * moved the whole evening chain earlier. Never quote a cron from source; read
 * {@code docker inspect}
 * (so a fresh stack has breadth history immediately); both are idempotent upserts and dedup on the
 * watermark. It intentionally does NOT listen for {@code BhavcopyBackfillCompleted} — the bhavcopy
 * module already depends on the {@code nse} module, so a reverse dependency here would form a module
 * cycle (Modulith). Cron-only mirrors the {@code MarketContextEodJob} EOD precedent; the small delay
 * from bhavcopy-landing to the cron is immaterial for an EOD materialization.
 * Fail-soft: a fold failure is logged, never fatal. Gated by {@code artha.breadth.materialize.enabled}.
 *
 * <p>Registered in the {@code ingest_runs} batch-source ledger under {@code EQUITY_BREADTH}: a {@code
 * RUNNING} → {@code SUCCESS}(rows)/{@code FAILURE} lifecycle via {@link IngestRunLedger#record}. The row
 * is visible on the ingest-health board's last-run join today; registration in {@code
 * IngestCoverageCanary.EXPECTED} (per-trading-day verdicts + the T+1 alert) is DELIBERATELY DEFERRED
 * until this job's first successful live run — adding an unproven source to the REQUIRE matrix would
 * false-RED the 08:45 canary before the first execution (the #745 MarketContextEodJob precedent).
 *
 * <p>On the first run the table is empty, so the boot pass backfills {@code backfill-days} of history
 * (the fold is one SMA-warmed scan over the deep cash bhavcopy — cheap, off the critical path); on later
 * runs it recomputes only from the last materialized date forward (usually one or two days, which also
 * re-corrects the last day if the bhavcopy was revised).
 */
@Component
public class EquityBreadthEodJob {

  private static final Logger log = LoggerFactory.getLogger(EquityBreadthEodJob.class);

  private final EquityBreadthDailyRepository repository;
  private final NseEodBhavcopyRepository bhavcopy;
  private final IngestRunLedger ledger;
  private final boolean enabled;
  private final int backfillDays;

  /** Wires the breadth writer, the bhavcopy watermark reader, the ingest ledger + the tunables. */
  public EquityBreadthEodJob(
      EquityBreadthDailyRepository repository,
      NseEodBhavcopyRepository bhavcopy,
      IngestRunLedger ledger,
      @Value("${artha.breadth.materialize.enabled:true}") boolean enabled,
      @Value("${artha.breadth.backfill-days:240}") int backfillDays) {
    this.repository = repository;
    this.bhavcopy = bhavcopy;
    this.ledger = ledger;
    this.enabled = enabled;
    this.backfillDays = backfillDays;
  }

  /** Boot one-shot — a fresh stack gets breadth history immediately. */
  @EventListener(ApplicationReadyEvent.class)
  void onStartup() {
    runQuietly("startup");
  }

  /** Daily cron; 18:51 IST as deployed. See the class javadoc — the value is env-driven. */
  @Scheduled(cron = "${artha.breadth.materialize-cron:0 51 18 * * MON-FRI}", zone = "Asia/Kolkata")
  void scheduled() {
    runQuietly("scheduled");
  }

  private void runQuietly(String trigger) {
    if (!enabled) {
      return;
    }
    // Ledger opened only after the dedup skip below, so a no-op run records nothing; the id survives
    // into the catch so a fold failure is recorded, not vanished.
    Long runId = null;
    try {
      // Cash-scoped, to match the cash-scoped population compute() now folds (H24 PR-6). This is
      // the second half of the mixed-watermark pair the ledger names; DataQualityEodJob was PR-5.
      LocalDate watermark = bhavcopy.maxCashTradeDate();
      if (watermark == null) {
        log.info("breadth materialization skipped ({}) — no bhavcopy rows yet", trigger);
        return;
      }
      LocalDate latest = repository.latestDate();
      if (latest != null && !latest.isBefore(watermark)) {
        log.debug("breadth already materialized through {} — skipped ({})", latest, trigger);
        return;
      }
      // Recompute from the last materialized date forward (re-corrects it too); first run backfills.
      LocalDate from = latest != null ? latest : watermark.minusDays(backfillDays);
      runId = ledger.start(IngestRunLedger.SOURCE_EQUITY_BREADTH);
      List<EquityBreadthDailyRepository.BreadthDay> days = repository.compute(from, watermark);
      int written = repository.upsertAll(days);
      ledger.succeed(runId, written);
      log.info(
          "breadth materialized {} day(s) [{}..{}] ({})", written, from, watermark, trigger);
    } catch (RuntimeException e) {
      ledger.fail(runId, e.getMessage());
      log.warn("breadth materialization failed ({}) — non-fatal", trigger, e);
    }
  }
}

package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillCompleted;
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
 * The PRIMARY trigger is {@link BhavcopyBackfillCompleted} — the fold keys on the bhavcopy watermark,
 * so it must run AFTER the day's EQ rows land (same rationale as the Minervini/Manas screens: the old
 * cron raced the backfill). A boot one-shot (so a fresh stack has breadth history immediately) and a
 * fallback cron (in case the backfill never publishes) remain; all three paths are idempotent upserts.
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
 * (the fold is one SMA-warmed scan over the deep EQ bhavcopy — cheap, off the critical path); on later
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
      @Value("${artha.breadth.backfill-days:180}") int backfillDays) {
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

  /** Primary trigger: the day's bhavcopy rows just landed — materialize against the fresh watermark. */
  @EventListener(BhavcopyBackfillCompleted.class)
  void onBhavcopyBackfillCompleted() {
    runQuietly("bhavcopy-complete");
  }

  /** Fallback cron (bhavcopy backfill failed → no event). 19:55, after the screens' 19:50. */
  @Scheduled(cron = "${artha.breadth.materialize-cron:0 55 19 * * MON-FRI}", zone = "Asia/Kolkata")
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
      LocalDate watermark = bhavcopy.maxTradeDate();
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

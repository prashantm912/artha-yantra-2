package in.arthayantra.marketdata.context;

import in.arthayantra.marketdata.context.DayContextService.DayContext;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.options.OptionsDigestService.OptionsDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The EOD day-context job (intelligence-layer design §6.6). After the 19:00/19:30 NSE ingests it
 * computes the day-context one-call for the primary index and persists exactly one {@code
 * market_context_days} row per IST session day — the day-context JSON plus the EOD baseline scalars a
 * next-day "vs prior day" read can consult, the queryable context history, and the §10 replay fixture.
 *
 * <p>Registered in the {@code ingest_runs} batch-source ledger under {@code MARKET_CONTEXT_DAY} (the
 * trust oracle): a {@code RUNNING} → {@code SUCCESS}(1)/{@code FAILURE} lifecycle via {@link
 * IngestRunLedger#record}, so the ingest-health board and the T+1 coverage canary can see whether the
 * day's context was captured — same fail-soft contract as every other EOD source (a crash leaves a
 * RUNNING row behind, never a silent hole).
 */
@Component
public class MarketContextEodJob {

  private static final Logger log = LoggerFactory.getLogger(MarketContextEodJob.class);

  private final DayContextService dayContext;
  private final MarketContextDayRepository repository;
  private final IngestRunLedger ledger;
  private final String optionsName;

  /** Wires the day-context service, the persistence writer, the ingest ledger, and the primary index. */
  public MarketContextEodJob(
      DayContextService dayContext,
      MarketContextDayRepository repository,
      IngestRunLedger ledger,
      @Value("${artha.context.options-name:NIFTY}") String optionsName) {
    this.dayContext = dayContext;
    this.repository = repository;
    this.ledger = ledger;
    this.optionsName = optionsName;
  }

  /**
   * Daily after the NSE EOD ingests (default 19:45 IST, MON-FRI). Its own try/catch keeps a fetch/
   * persist failure logged and non-fatal — the ledger has already recorded the FAILURE and rethrown.
   */
  @Scheduled(cron = "${artha.context.eod-cron:0 45 19 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    try {
      ledger.record(IngestRunLedger.SOURCE_MARKET_CONTEXT_DAY, this::persist);
    } catch (RuntimeException failed) {
      log.warn("day-context EOD persist failed (will retry next schedule): {}", failed.getMessage());
    }
  }

  /** Compute the day context and upsert its row; returns the rows written for the ledger. */
  private long persist() {
    DayContext dc = dayContext.dayContext();
    // The MON-FRI cron also fires on weekday NSE holidays; on a non-trading day dc.tradeDate() is the
    // PRIOR session, so persisting would overwrite that day's real row with holiday-flavored context.
    // Skip cleanly (a SUCCESS/0 ledger row = "ran, nothing to persist"), mirroring an empty EOD pull.
    if ("HOLIDAY".equals(dc.sessionPhase())) {
      log.info("day-context EOD skipped — {} is not a trading day", dc.tradeDate());
      return 0L;
    }
    OptionsDigest o = dc.options();
    int rows =
        repository.upsert(
            dc.tradeDate(),
            optionsName,
            o == null ? null : o.expiry(),
            o == null || o.pcr() == null ? null : o.pcr().now(),
            o == null || o.maxPain() == null ? null : o.maxPain().now(),
            o == null || o.atmStraddle() == null ? null : o.atmStraddle().now(),
            o == null || o.atmIv() == null ? null : o.atmIv().iv(),
            dc);
    log.info("day-context EOD persisted market_context_days for {} ({} row)", dc.tradeDate(), rows);
    return rows;
  }
}

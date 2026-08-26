package in.arthayantra.marketdata.context;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.context.DayContextService.DayContext;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.instruments.UnderlyingRef;
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
 * <p>Registered in the {@code ingest_runs} batch-source ledger under {@code MARKET_CONTEXT_DAY}: a
 * {@code RUNNING} → {@code SUCCESS}(rows)/{@code FAILURE} lifecycle via {@link IngestRunLedger#record}
 * (a crash leaves a RUNNING row behind, never a silent hole). The row is visible on the ingest-health
 * board's last-run join today; registration in {@code IngestCoverageCanary.EXPECTED} (per-trading-day
 * verdicts + the T+1 alert) is DELIBERATELY DEFERRED until the job's first successful live run —
 * adding an unproven source to the REQUIRE matrix would false-RED the 08:45 canary before the first
 * 19:45 execution.
 *
 * <p><b>Stale-anchor guard (§6.5).</b> On a trading day with NO snapshot capture (the 2026-07-08
 * outage class) the options digest anchors on the PRIOR session's newest bucket — its values are
 * yesterday's truth. The EOD scalar baseline columns are written ONLY when the digest's {@code asOf}
 * falls on the row's {@code trade_date}; otherwise they are NULL (an honest hole) while the full
 * digest stays in the JSONB for diagnosis.
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
      @Value("${artha.context.options-name:NIFTY 50}") String optionsName) {
    this.dayContext = dayContext;
    this.repository = repository;
    this.ledger = ledger;
    // ⚠️ NORMALISED here TOO, and this is the copy that matters most for diagnosis: this value is
    // written raw into market_context_days.options_name, the column that made the 26-row gap legible
    // after the fact. Leaving it un-normalised while DayContextService normalises would let
    // ARTHA_CONTEXT_OPTIONS_NAME=NIFTY produce a WORKING digest stamped with a label that does not
    // match the scalars beside it. Caught in review of the very PR whose stated doctrine is
    // "normalise at the point of USE rather than keep correcting copies" -- this WAS a second copy.
    this.optionsName = UnderlyingRef.canonical(optionsName);
  }

  /**
   * Daily after the NSE EOD ingests (default 19:45 IST, MON-FRI). Its own try/catch keeps a fetch/
   * persist failure logged and non-fatal — the ledger has already recorded the FAILURE and rethrown.
   */
  @Scheduled(cron = "${artha.context.eod-cron:0 49 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    try {
      ledger.record(IngestRunLedger.SOURCE_MARKET_CONTEXT_DAY, this::persist);
    } catch (RuntimeException failed) {
      log.warn("day-context EOD persist failed (will retry next schedule): {}", failed.getMessage());
    }
  }

  /** Compute the day context and upsert its row; returns the rows written for the ledger. */
  private long persist() {
    // ⚠️ freshDayContext(), NEVER dayContext(). This row is the day's CLOSING context and there is
    // exactly one of it per session, so it must never be assembled from the H31 intraday snapshot.
    // The 08-15 refresh window and the 300 s max age make that impossible TODAY by arithmetic — but
    // both are independently configurable, and widening either would silently start persisting a
    // mid-afternoon context as the close, with no change to this file and nothing to review. The
    // uncached entry point removes the dependency rather than documenting it.
    DayContext dc = dayContext.freshDayContext();
    // The MON-FRI cron also fires on weekday NSE holidays; on a non-trading day dc.tradeDate() is the
    // PRIOR session, so persisting would overwrite that day's real row with holiday-flavored context.
    // Skip cleanly (a SUCCESS/0 ledger row = "ran, nothing to persist"), mirroring an empty EOD pull.
    if ("HOLIDAY".equals(dc.sessionPhase())) {
      log.info("day-context EOD skipped — {} is not a trading day", dc.tradeDate());
      return 0L;
    }
    return persistRow(dc);
  }

  /**
   * Upsert the day's row. The options scalar baselines land ONLY when the digest is anchored ON the
   * trade date (see the class javadoc's stale-anchor guard); a stale digest yields NULL scalars + the
   * full digest in the JSONB. Package-private for the stale-anchor IT.
   */
  long persistRow(DayContext dc) {
    OptionsDigest o = dc.options();
    boolean anchoredOnTradeDate =
        o != null
            && o.asOf() != null
            && o.asOf().atZoneSameInstant(Ist.ZONE).toLocalDate().equals(dc.tradeDate());
    if (o != null && !anchoredOnTradeDate) {
      log.warn(
          "day-context EOD: options digest for {} anchors on {} (not the trade date) — writing NULL"
              + " scalar baselines, full digest kept in day_context",
          dc.tradeDate(),
          o.asOf());
    }
    int rows =
        repository.upsert(
            dc.tradeDate(),
            optionsName,
            o == null ? null : o.expiry(),
            anchoredOnTradeDate && o.pcr() != null ? o.pcr().now() : null,
            anchoredOnTradeDate && o.maxPain() != null ? o.maxPain().now() : null,
            anchoredOnTradeDate && o.atmStraddle() != null ? o.atmStraddle().now() : null,
            anchoredOnTradeDate && o.atmIv() != null ? o.atmIv().iv() : null,
            dc);
    log.info("day-context EOD persisted market_context_days for {} ({} row)", dc.tradeDate(), rows);
    return rows;
  }
}

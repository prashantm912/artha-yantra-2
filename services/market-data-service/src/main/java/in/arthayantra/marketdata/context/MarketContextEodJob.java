package in.arthayantra.marketdata.context;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.context.DayContextService.DayContext;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.instruments.UnderlyingRef;
import in.arthayantra.marketdata.options.OptionsDigestService.OptionsDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
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
  private final Clock clock;

  /**
   * The job's OWN schedule, parsed from the same property {@link #run()} is annotated with, so the
   * boot catch-up can ask "has today's slot already passed?" without a second copy of the time.
   * A duplicated literal here would be the #653 matched-defaults trap: an operator overriding the
   * cron would move the job and leave the catch-up guarding the old hour, silently.
   *
   * <p>{@code null} when the job is disabled Spring's documented way ({@code cron = "-"}), in
   * which case there is no scheduled pass and therefore nothing to catch up.
   */
  private final CronExpression eodSchedule;

  /** Wires the day-context service, the persistence writer, the ingest ledger, and the primary index. */
  public MarketContextEodJob(
      DayContextService dayContext,
      MarketContextDayRepository repository,
      IngestRunLedger ledger,
      Clock clock,
      @Value("${artha.context.eod-cron:0 49 18 * * MON-FRI}") String eodCron,
      @Value("${artha.context.options-name:NIFTY 50}") String optionsName) {
    this.dayContext = dayContext;
    this.repository = repository;
    this.ledger = ledger;
    this.clock = clock;
    this.eodSchedule =
        Scheduled.CRON_DISABLED.equals(eodCron) ? null : CronExpression.parse(eodCron);
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

  /**
   * Replay a missed EOD pass on boot. A cron NEVER backfills: if the box is down at the scheduled
   * minute the row is simply absent, and unlike the bhavcopy and screen legs this job had no
   * catch-up door at all — so a late boot lost the session silently and permanently.
   *
   * <p>⚠️ <b>MEASURED, not hypothetical.</b> On 2026-09-01 a power cut took the host down from
   * 12:42 IST; it returned at 18:47 and the containers were up at 18:48:59 — <b>one second</b>
   * before this job's 18:49 slot, which therefore passed during Spring startup. Every other evening
   * leg was rescued by its own boot catch-up; this one was not, and that session's
   * {@code market_context_days} row does not exist and can never be reconstructed.
   *
   * <p>⚠️ <b>The window is narrow ON PURPOSE, and widening it would be actively harmful.</b>
   * {@link DayContextService#freshDayContext()} derives {@code tradeDate} from <em>now</em>
   * ({@code tradingDay ? today : previousTradingDay(today)}), so firing this on a later morning
   * would compute a PRE_OPEN context for the NEW day, upsert a premature row for a session that has
   * not traded, and still leave the missing day missing. The only moment the job reconstructs the
   * intended session is the same day, after its own slot — which is exactly what the guard below
   * encodes.
   *
   * <p>Deliberately NOT a recovery for an outage that outlasts the day: yesterday's closing context
   * reads live VIX and chain state, and that moment is gone. This stops the hole recurring; it
   * cannot fill one already made.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void catchUpOnBoot() {
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(Ist.ZONE));
    if (!scheduledPassIsAlreadyPast(now)) {
      log.info(
          "day-context EOD boot catch-up: today's slot has not passed at {} IST — leaving it to the"
              + " cron",
          now.toLocalTime());
      return;
    }
    // ⚠️ Keyed on the ROW, never on an ingest_runs SUCCESS: a holiday skip also writes SUCCESS while
    // persisting nothing, so a ledger-keyed guard would read a skipped holiday as a completed pass.
    if (repository.existsFor(now.toLocalDate())) {
      log.info("day-context EOD boot catch-up: {} already has a row — nothing to replay",
          now.toLocalDate());
      return;
    }
    log.warn(
        "day-context EOD boot catch-up: {} slot passed with no row — replaying it now",
        now.toLocalDate());
    run();
  }

  /**
   * Whether this job's own cron fire time for {@code now}'s IST date is at or before {@code now}.
   * Mirrors {@code BhavcopyCloseCanary.scheduledPassIsAlreadyPast} rather than inventing a second
   * shape for the same question.
   */
  private boolean scheduledPassIsAlreadyPast(ZonedDateTime now) {
    if (eodSchedule == null) {
      return false;
    }
    ZonedDateTime firstToday =
        eodSchedule.next(now.toLocalDate().atStartOfDay(Ist.ZONE).minusNanos(1));
    // Not-today covers the cron not firing on this date at all (a MON-FRI cron on a Saturday boot):
    // there is no missed pass to replay.
    return firstToday != null
        && firstToday.toLocalDate().equals(now.toLocalDate())
        && !firstToday.isAfter(now);
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

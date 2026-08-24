package in.arthayantra.marketdata.ingest;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writer for the {@code marketdata.ingest_runs} ledger (audit 2026-07-10 §7.2.3 / §9.1 — the
 * batch-source trust oracle). One row per scheduled/triggered ingest: a {@code RUNNING} row at start,
 * stamped {@code SUCCESS} (with the row count) or {@code FAILURE} (with the error) at finish.
 *
 * <p>Two invariants:
 *
 * <ol>
 *   <li><b>Record, never swallow.</b> {@link #record} rethrows the job's exception after marking the
 *       run {@code FAILURE} — a caller's existing exception handling is preserved unchanged. A run
 *       that crashes before finishing leaves its {@code RUNNING} row behind (a stall signal), it does
 *       not vanish.
 *   <li><b>Fail-soft writes.</b> Every ledger write is wrapped: a logging-DB hiccup must never break
 *       the ingest it audits. A failed insert yields a {@code null} run id (the caller's work still
 *       runs, just unaudited); a failed status update is logged and dropped.
 * </ol>
 */
@Repository
public class IngestRunLedger {

  // The enumerated ingest sources (audit §7.2.3). Constants keep call sites typo-proof + discoverable.
  public static final String SOURCE_NSE_FII_DII = "NSE_FII_DII";
  public static final String SOURCE_NSE_PARTICIPANT_OI = "NSE_PARTICIPANT_OI";
  public static final String SOURCE_NSE_FII_DERIVATIVE = "NSE_FII_DERIVATIVE";
  public static final String SOURCE_BHAVCOPY = "BHAVCOPY";
  public static final String SOURCE_MINERVINI_SCREEN = "MINERVINI_SCREEN";
  public static final String SOURCE_MANAS_SCREEN = "MANAS_SCREEN";
  public static final String SOURCE_INSTRUMENT_SYNC = "INSTRUMENT_SYNC";
  public static final String SOURCE_OPTIONS_SNAPSHOT_CAPTURE = "OPTIONS_SNAPSHOT_CAPTURE";
  // Intelligence-layer INT I1 (design §6.6): the daily EOD day-context persistence (market_context_days).
  public static final String SOURCE_MARKET_CONTEXT_DAY = "MARKET_CONTEXT_DAY";
  // Audit §3.3/§6.10 (Phase-3): the daily market-breadth materialization (equity_breadth_daily).
  public static final String SOURCE_EQUITY_BREADTH = "EQUITY_BREADTH";
  // FID P2-4 / audit D8: nightly per-symbol chain, 1m, and EQ-bhavcopy completeness.
  public static final String SOURCE_DATA_QUALITY = "DATA_QUALITY";
  // The 18:58 bhavcopy-vs-Kite close comparison (BhavcopyCloseCanary). Not an ingest — it is the last
  // market-data job before the 19:00 shutdown, and EveningChainCanary needs a terminal row to know it
  // has run. See BhavcopyCloseCanary#sweep for why the skip path records STATUS_SKIPPED.
  public static final String SOURCE_BHAVCOPY_CLOSE = "BHAVCOPY_CLOSE";

  /**
   * The two strategy-signal insight sweeps that run inside the evening window (18:56 and 18:57) and
   * close the chain after market-data's own legs.
   *
   * <p>⚠️ These rows are written by ANOTHER service, over HTTP — see {@link
   * in.arthayantra.marketdata.canary.EveningChainLegController} for why that direction, and why the
   * rows land in {@code marketdata.ingest_runs} rather than anywhere in the {@code strategy} schema.
   */
  public static final String SOURCE_INSIGHT_STRATEGY_EVIDENCE = "INSIGHT_STRATEGY_EVIDENCE";

  public static final String SOURCE_INSIGHT_SELL_DECISION = "INSIGHT_SELL_DECISION";

  /**
   * A run that legitimately did not happen and will not happen again for this session — terminal, but
   * emphatically not {@code SUCCESS}.
   *
   * <p>Introduced for {@code BhavcopyCloseCanary}'s date guard, whose own javadoc says the skip is
   * "PERMANENTLY missed for that session, not deferred". To {@code EveningChainCanary} that is DONE
   * (nothing more will run tonight, which is the shutdown question); to a reader it must not claim a
   * comparison happened. Recording it {@code SUCCESS} with zero rows would be exactly the class of
   * quiet lie this ledger exists to prevent.
   *
   * <p>The column is free-form {@code TEXT} with no CHECK (V040), so this needs no migration, and no
   * consumer enumerates statuses generically: {@code IngestHealthBoard} pivots {@code
   * IngestCoverageCanary.EXPECTED} (which contains none of the sources above), and {@code
   * EveningChainCanary} tests only for {@code RUNNING} and {@code FAILURE} by name.
   */
  public static final String STATUS_SKIPPED = "SKIPPED";

  private static final Logger log = LoggerFactory.getLogger(IngestRunLedger.class);

  private final JdbcTemplate jdbc;

  public IngestRunLedger(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A unit of ingest work whose row count is recorded; any thrown exception marks the run FAILURE. */
  @FunctionalInterface
  public interface IngestJob {
    /** @return the number of rows this run wrote. */
    long run();
  }

  /**
   * Insert a {@code RUNNING} row for {@code source}; returns its id, or {@code null} if the ledger
   * write itself failed (fail-soft — the caller still runs, just unaudited).
   */
  public Long start(String source) {
    try {
      return jdbc.queryForObject(
          "INSERT INTO ingest_runs (source, status, started_at) VALUES (?, 'RUNNING', now()) RETURNING id",
          Long.class,
          source);
    } catch (RuntimeException e) {
      log.warn("ingest_runs start failed for {} — run will not be audited: {}", source, e.getMessage());
      return null;
    }
  }

  /**
   * Stamp the run {@code SUCCESS} with its row count. No-op when {@code id} is null; never throws.
   *
   * <p>{@code error} is cleared, not left as-is: a SUCCESS row carrying error text is a contradiction
   * every downstream reader has to disambiguate. No caller writes an error on a success path today,
   * so this is defence rather than repair — specifically against {@link IngestRunReaper} having
   * stamped {@code REAPED_ON_BOOT} on a row that then went on to finish, which would otherwise
   * persist for the row's whole life.
   */
  public void succeed(Long id, long rowsWritten) {
    if (id == null) {
      return;
    }
    try {
      jdbc.update(
          "UPDATE ingest_runs SET status='SUCCESS', rows_written=?, finished_at=now(), error=NULL"
              + " WHERE id=?",
          rowsWritten,
          id);
    } catch (RuntimeException e) {
      log.warn("ingest_runs succeed update failed for id {} — non-fatal: {}", id, e.getMessage());
    }
  }

  /**
   * Stamp the run {@link #STATUS_SKIPPED} with the reason it did not run. No-op when {@code id} is
   * null; never throws. Terminal, like SUCCESS and FAILURE — use it only where the work will NOT be
   * retried for this session, never where it is merely deferred.
   */
  public void skip(Long id, String reason) {
    if (id == null) {
      return;
    }
    try {
      jdbc.update(
          "UPDATE ingest_runs SET status=?, error=?, finished_at=now() WHERE id=?",
          STATUS_SKIPPED,
          reason,
          id);
    } catch (RuntimeException e) {
      log.warn("ingest_runs skip update failed for id {} — non-fatal: {}", id, e.getMessage());
    }
  }

  /**
   * Record a run that already happened elsewhere: one row, inserted terminal, with the caller's own
   * measured start and finish rather than {@code now()}.
   *
   * <p>The start/finish pair is supplied because the only caller is the cross-service leg report (see
   * {@code EveningChainLegController}) — the run happened in another process and the row must carry
   * ITS timings, not the moment the report arrived. Everything in-process should keep using {@link
   * #record}, which cannot mis-state a time it did not measure.
   *
   * <p>Fail-soft like every other write here, but the caller is an HTTP handler, so it needs to know:
   * returns whether the row landed.
   */
  public boolean recordCompleted(
      String source, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt, String error) {
    try {
      jdbc.update(
          "INSERT INTO ingest_runs (source, status, started_at, finished_at, error)"
              + " VALUES (?, ?, ?, ?, ?)",
          source,
          status,
          startedAt,
          finishedAt,
          error);
      return true;
    } catch (RuntimeException e) {
      log.warn("ingest_runs completed-run insert failed for {} — non-fatal: {}", source, e.getMessage());
      return false;
    }
  }

  /** Stamp the run {@code FAILURE} with the error. No-op when {@code id} is null; never throws. */
  public void fail(Long id, String error) {
    if (id == null) {
      return;
    }
    try {
      jdbc.update(
          "UPDATE ingest_runs SET status='FAILURE', error=?, finished_at=now() WHERE id=?", error, id);
    } catch (RuntimeException e) {
      log.warn("ingest_runs fail update failed for id {} — non-fatal: {}", id, e.getMessage());
    }
  }

  /**
   * True when {@code source} already has a SUCCESS row for TODAY (IST) that actually WROTE
   * something.
   *
   * <p>Exists for the intra-day retry: a source that already landed must not be re-fetched, both
   * because it is pointless and because NSE's anti-bot behaviour makes needless requests a real cost
   * (this scheduler's class javadoc calls that out).
   *
   * <p>⚠️ {@code status} ALONE is not enough, and this is the whole reason the row count is in the
   * predicate. {@link #record} stamps SUCCESS on any non-throwing return, and two real paths return
   * normally having stored nothing: {@code LiveFiiDiiFetcher} iterates an empty JSON array to an
   * empty list, and {@code LiveParticipantOiFetcher}'s {@code csv.contains("Client Type")} guard is
   * satisfied by the HEADER LINE alone, so a truncated file parses to zero rows. Both are the
   * 200-that-is-not-data shape NSE anti-bot actually produces — and on {@code status} alone each
   * would record SUCCESS and permanently disarm that day's remaining retries, in the exact failure
   * mode the retry exists for. {@code status} was an audit field before it became a control input.
   *
   * <p>⚠️ Honest limit: {@code rows_written} counts rows SUBMITTED to an upsert, not rows STORED
   * — the NSE upserts are {@code ON CONFLICT DO UPDATE}, so a re-fetch of unchanged data still
   * counts. This closes the EMPTY-RESPONSE hole and not the general one; do not read a positive
   * count as proof that new data landed.
   *
   * <p>⚠️ The date comparison is IST on BOTH sides, deliberately. In-container {@code now()} is UTC,
   * so a bare {@code started_at::date} would roll the "today" boundary at 05:30 IST and make an
   * early-morning run look like yesterday's. Same trap the repo records for candle buckets.
   *
   * <p>Fail-soft: any query error returns {@code false}, i.e. "not known to have succeeded", so the
   * retry runs. That direction is deliberate — a wasted fetch is cheaper than a rail silently dark
   * for a session, which is the defect this supports.
   */
  public boolean succeededToday(String source) {
    try {
      Boolean found =
          jdbc.queryForObject(
              "SELECT EXISTS (SELECT 1 FROM ingest_runs WHERE source = ? AND status = 'SUCCESS'"
                  + " AND COALESCE(rows_written, 0) > 0"
                  + " AND (started_at AT TIME ZONE 'Asia/Kolkata')::date"
                  + "   = (now() AT TIME ZONE 'Asia/Kolkata')::date)",
              Boolean.class,
              source);
      return Boolean.TRUE.equals(found);
    } catch (RuntimeException e) {
      log.warn("ingest_runs succeededToday check failed for {} — assuming NOT: {}", source, e.getMessage());
      return false;
    }
  }

  /**
   * Record a start/finish run around {@code job}: {@code RUNNING} → {@code SUCCESS}(rows) on return,
   * or {@code FAILURE}(message) then RETHROW on exception. The rethrow preserves the caller's existing
   * exception handling — this records the outcome, it does not consume it.
   */
  public void record(String source, IngestJob job) {
    Long id = start(source);
    try {
      long rows = job.run();
      succeed(id, rows);
    } catch (RuntimeException e) {
      fail(id, e.getMessage());
      throw e;
    }
  }

  /**
   * Daily capture-session summary — {@code OPTIONS_SNAPSHOT_CAPTURE}, the one §7.2.3 "1 row/day"
   * source. Keeps a single {@code SUCCESS} row per IST session day whose {@code rows_written}
   * accumulates across the day's 2-min capture passes and whose {@code finished_at} advances to the
   * latest pass ({@code started_at} stays at the first). Fully fail-soft — a ledger hiccup must never
   * break a capture pass.
   *
   * @param sessionDay start-of-day (IST) marking the session; the per-day dedup key
   * @param rowsThisPass rows persisted by the pass just completed
   */
  public void recordCaptureSession(OffsetDateTime sessionDay, long rowsThisPass) {
    try {
      jdbc.update(
          """
          INSERT INTO ingest_runs (source, window_start, status, rows_written, started_at, finished_at)
          VALUES ('OPTIONS_SNAPSHOT_CAPTURE', ?, 'SUCCESS', ?, now(), now())
          ON CONFLICT (source, window_start) WHERE source = 'OPTIONS_SNAPSHOT_CAPTURE'
          DO UPDATE SET rows_written = ingest_runs.rows_written + EXCLUDED.rows_written,
                        finished_at = now()
          """,
          sessionDay,
          rowsThisPass);
    } catch (RuntimeException e) {
      log.warn("ingest_runs capture-session upsert failed — non-fatal: {}", e.getMessage());
    }
  }
}

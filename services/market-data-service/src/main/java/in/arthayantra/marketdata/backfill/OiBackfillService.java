package in.arthayantra.marketdata.backfill;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.futures.FuturesOiSnapshotRepository;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.OiHistorySource;
import in.arthayantra.marketdata.options.OptionsSnapshotRepository;
import in.arthayantra.marketdata.options.OptionsSnapshotRepository.SnapshotRow;
import in.arthayantra.marketdata.upstox.BackfillJobRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * OI-backfill importer (data-foundation milestone, plan §2 / build-task #3). Populates the existing
 * OI snapshot tables for a past session so the Wave-1 data pages can be value-verified in History
 * mode: each chain leg's 1m OHLC+OI is fetched via the dedicated {@link OiHistorySource} (OpenAlgo
 * {@code /history}, Upstox-backed), sampled to the snapshot cadence (options 5-min, futures 3-min),
 * and written {@code source='BACKFILL'} via the idempotent {@code insertBackfill} path — so a re-run
 * is a no-op and backfilled OI never mixes with live capture.
 *
 * <ul>
 *   <li><b>oi_change</b> = OI(bucket) − OI(prior bucket) for the same leg (null on the first bucket,
 *       matching the live first-pass).
 *   <li><b>ltp</b> = the bucket's close; <b>spot</b> = the index 1m close at the same 5-min bucket.
 *   <li><b>greeks/IV</b> are left null (OI pages don't use them; per-bucket recompute needs
 *       forward/r/t — overkill for verify-now). {@code iv_reason}/{@code price_source} = {@code
 *       BACKFILL} mark the provenance on the row too.
 * </ul>
 *
 * <p>The OpenAlgo source bean is present only on the live profile with {@code
 * artha.openalgo.oi-backfill-enabled=true}; absent → the trigger 503s as unconfigured. A single run
 * holds an in-process lock (409 on overlap) and executes on a daemon thread (the importer 202s).
 */
@Service
public class OiBackfillService {

  private static final Logger log = LoggerFactory.getLogger(OiBackfillService.class);

  private static final LocalTime SESSION_START = LocalTime.of(9, 0);
  private static final LocalTime SESSION_END = LocalTime.of(15, 35);
  private static final int OPTIONS_CADENCE_MIN = 5;
  private static final int FUTURES_CADENCE_MIN = 3;
  private static final String BACKFILL = "BACKFILL";

  /** Outcome of one backfill run — the row counts written to each snapshot table. */
  public record BackfillResult(
      String jobId,
      String underlying,
      LocalDate expiry,
      LocalDate session,
      int optionRows,
      int futuresRows) {}

  /**
   * Live + last-run audit, surfaced by {@code GET /api/v1/market/admin/oi-backfill/status} (B1).
   * Mirrors the {@code BhavcopyBackfillService.Status} state machine (NEVER_RUN→RUNNING→OK|FAILED).
   */
  public record Status(
      String jobId,
      String state, // NEVER_RUN | RUNNING | OK | FAILED
      String underlying,
      LocalDate expiry,
      LocalDate session,
      Instant startedAt,
      Instant lastRun,
      long durationMs,
      int optionRows,
      int futuresRows,
      String error) {

    static Status neverRun() {
      return new Status(null, "NEVER_RUN", null, null, null, null, null, 0, 0, 0, null);
    }
  }

  private final AtomicReference<Status> status = new AtomicReference<>(Status.neverRun());
  private final ObjectProvider<OiHistorySource> historySource;
  private final InstrumentRepository instruments;
  private final OptionsSnapshotRepository optionsRepository;
  private final FuturesOiSnapshotRepository futuresRepository;
  private final FuturesContractSource futuresContracts;
  /** Run-audit ledger (V030) — this path wrote NO row before audit V12; writes fail-soft here. */
  private final BackfillJobRepository jobRepo;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "oi-backfill");
            t.setDaemon(true);
            return t;
          });

  /** Wires the backfill inputs; the history source is optional (absent unless flag-enabled). */
  public OiBackfillService(
      ObjectProvider<OiHistorySource> historySource,
      InstrumentRepository instruments,
      OptionsSnapshotRepository optionsRepository,
      FuturesOiSnapshotRepository futuresRepository,
      FuturesContractSource futuresContracts,
      BackfillJobRepository jobRepo) {
    this.historySource = historySource;
    this.instruments = instruments;
    this.optionsRepository = optionsRepository;
    this.futuresRepository = futuresRepository;
    this.futuresContracts = futuresContracts;
    this.jobRepo = jobRepo;
  }

  /**
   * 202-style async trigger: validates the source + acquires the single-run lock SYNCHRONOUSLY (so
   * the caller gets the 503/409 immediately), then runs the backfill on the daemon thread.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public Map<String, String> triggerAsync(String underlying, LocalDate expiry, LocalDate session) {
    requireSource();
    if (!running.compareAndSet(false, true)) {
      throw new ApiException(
          409, ErrorCodes.CONFLICT_BACKFILL_RUNNING, "an OI backfill is already in progress");
    }
    String jobId = UUID.randomUUID().toString();
    Instant started = Instant.now();
    status.set(
        new Status(
            jobId, "RUNNING", underlying, expiry, session, started, null, 0, 0, 0, null));
    long auditId = auditStart(jobId, underlying, expiry, session);
    executor.submit(
        () -> {
          try {
            BackfillResult result = runBackfill(underlying, expiry, session, jobId);
            log.info(
                "oi-backfill {} done: {} {} {} → {} option rows, {} futures rows",
                jobId, underlying, expiry, session, result.optionRows(), result.futuresRows());
            auditComplete(auditId, (long) result.optionRows() + result.futuresRows());
            Instant ended = Instant.now();
            status.set(
                new Status(
                    jobId, "OK", underlying, expiry, session, started, ended,
                    java.time.Duration.between(started, ended).toMillis(),
                    result.optionRows(), result.futuresRows(), null));
          } catch (Exception e) {
            log.error("oi-backfill {} failed for {} {} {}", jobId, underlying, expiry, session, e);
            auditFail(auditId, e.toString());
            Instant ended = Instant.now();
            status.set(
                new Status(
                    jobId, "FAILED", underlying, expiry, session, started, ended,
                    java.time.Duration.between(started, ended).toMillis(), 0, 0, e.toString()));
          } finally {
            running.set(false);
          }
        });
    return Map.of("jobId", jobId);
  }

  /** Last-run audit (state + row tallies), surfaced by {@code GET …/oi-backfill/status} (B1). */
  public Status status() {
    return status.get();
  }

  /** One synchronous backfill pass (the unit of work the async path wraps). */
  public BackfillResult runBackfill(
      String underlying, LocalDate expiry, LocalDate session, String jobId) {
    OiHistorySource source = requireSource();
    Instant from = session.atTime(SESSION_START).atOffset(Ist.OFFSET).toInstant();
    Instant to = session.atTime(SESSION_END).atOffset(Ist.OFFSET).toInstant();
    int optionRows = backfillOptions(source, underlying, expiry, from, to);
    int futuresRows = backfillFutures(source, underlying, session, from, to);
    return new BackfillResult(jobId, underlying, expiry, session, optionRows, futuresRows);
  }

  /** Each CE/PE leg → 5-min snapshot rows (oi_change vs prior bucket, spot from the index series). */
  private int backfillOptions(
      OiHistorySource source, String underlying, LocalDate expiry, Instant from, Instant to) {
    List<Instrument> chain = instruments.optionChain(underlying, expiry);
    if (chain.isEmpty()) {
      return 0;
    }
    String indexExchange =
        chain.get(0).underlyingExchange() == null ? "NSE" : chain.get(0).underlyingExchange();
    Map<OffsetDateTime, BigDecimal> spotByBucket =
        spotByBucket(source, indexExchange, underlying, from, to);

    List<SnapshotRow> rows = new ArrayList<>();
    for (Instrument leg : chain) {
      InstrumentKey key = new InstrumentKey(leg.exchange(), leg.tradingsymbol());
      List<OiBackfillSampler.Bucket> buckets =
          OiBackfillSampler.sample(source.oneMinute(key, from, to), OPTIONS_CADENCE_MIN);
      Long prevOi = null;
      long cumulativeVolume = 0;
      for (OiBackfillSampler.Bucket b : buckets) {
        cumulativeVolume += b.volume();
        Long oiChange = (b.oi() != null && prevOi != null) ? b.oi() - prevOi : null;
        rows.add(
            new SnapshotRow(
                b.start(), underlying, expiry, leg.strike(), leg.instrumentType(),
                leg.tradingsymbol(), b.close(), null, null, cumulativeVolume, b.oi(), oiChange,
                spotByBucket.get(b.start()), null, null, null, null, null, null,
                BACKFILL, BACKFILL, null, null));
        if (b.oi() != null) {
          prevOi = b.oi();
        }
      }
    }
    optionsRepository.insertBackfill(rows);
    return rows.size();
  }

  /** Each monthly FUT → 3-min snapshot rows (running session O/H/L, oi_change vs prior bucket). */
  private int backfillFutures(
      OiHistorySource source, String underlying, LocalDate session, Instant from, Instant to) {
    List<FuturesContractSource.FutContract> contracts;
    try {
      contracts = futuresContracts.monthlyFutures(underlying, session);
    } catch (RuntimeException resolveFailed) {
      log.warn("oi-backfill futures resolve failed for {}: {}", underlying, resolveFailed.getMessage());
      return 0;
    }
    List<FuturesOiSnapshotRepository.Row> rows = new ArrayList<>();
    for (FuturesContractSource.FutContract contract : contracts) {
      List<OiBackfillSampler.Bucket> buckets =
          OiBackfillSampler.sample(source.oneMinute(contract.key(), from, to), FUTURES_CADENCE_MIN);
      if (buckets.isEmpty()) {
        continue;
      }
      BigDecimal dayOpen = buckets.get(0).open();
      BigDecimal dayHigh = null;
      BigDecimal dayLow = null;
      Long prevOi = null;
      long cumulativeVolume = 0;
      for (OiBackfillSampler.Bucket b : buckets) {
        dayHigh = dayHigh == null ? b.high() : dayHigh.max(b.high());
        dayLow = dayLow == null ? b.low() : dayLow.min(b.low());
        cumulativeVolume += b.volume();
        Long oiChange = (b.oi() != null && prevOi != null) ? b.oi() - prevOi : null;
        rows.add(
            new FuturesOiSnapshotRepository.Row(
                b.start(), underlying, contract.key().tradingsymbol(), contract.expiry(),
                b.close(), cumulativeVolume, b.oi(), oiChange, dayOpen, dayHigh, dayLow, null));
        if (b.oi() != null) {
          prevOi = b.oi();
        }
      }
    }
    futuresRepository.insertBackfill(rows);
    return rows.size();
  }

  /** Index 1m close at each 5-min bucket — the {@code spot_price} for the option rows. */
  private Map<OffsetDateTime, BigDecimal> spotByBucket(
      OiHistorySource source, String exchange, String underlying, Instant from, Instant to) {
    Map<OffsetDateTime, BigDecimal> out = new HashMap<>();
    InstrumentKey key = new InstrumentKey(exchange, underlying);
    for (OiBackfillSampler.Bucket b :
        OiBackfillSampler.sample(source.oneMinute(key, from, to), OPTIONS_CADENCE_MIN)) {
      out.put(b.start(), b.close());
    }
    return out;
  }

  private OiHistorySource requireSource() {
    OiHistorySource source = historySource.getIfAvailable();
    if (source == null) {
      throw new ApiException(
          503,
          ErrorCodes.NOT_CONFIGURED,
          "OI backfill source not configured "
              + "(set artha.openalgo.oi-backfill-enabled=true on the live profile)");
    }
    return source;
  }

  /**
   * Opens a {@code backfill_jobs} audit row for this run — kind {@code OI}, carrying the API job handle
   * (audit V12: this path wrote NO ledger row before). Best-effort: a logging-DB failure returns -1 and
   * is swallowed with a warn so the audit never breaks the backfill it records.
   */
  private long auditStart(String jobId, String underlying, LocalDate expiry, LocalDate session) {
    try {
      String params =
          String.format(
              "{\"underlying\":%s,\"expiry\":%s,\"session\":%s}",
              jsonStr(underlying), jsonOrNull(expiry), jsonOrNull(session));
      return jobRepo.start("OI", jobId, params);
    } catch (RuntimeException e) {
      log.warn("oi-backfill: could not open audit row (non-fatal): {}", e.toString());
      return -1;
    }
  }

  private void auditComplete(long auditId, long rows) {
    if (auditId < 0) {
      return;
    }
    try {
      jobRepo.complete(auditId, rows);
    } catch (RuntimeException e) {
      log.warn("oi-backfill: could not close audit row (non-fatal): {}", e.toString());
    }
  }

  private void auditFail(long auditId, String error) {
    if (auditId < 0) {
      return;
    }
    try {
      jobRepo.fail(auditId, error);
    } catch (RuntimeException e) {
      log.warn("oi-backfill: could not mark audit row failed (non-fatal): {}", e.toString());
    }
  }

  private static String jsonStr(String value) {
    return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
  }

  private static String jsonOrNull(LocalDate date) {
    return date == null ? "null" : "\"" + date + "\"";
  }
}

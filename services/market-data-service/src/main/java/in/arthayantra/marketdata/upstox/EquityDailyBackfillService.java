package in.arthayantra.marketdata.upstox;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Phase-5 prerequisite — the one-time <b>200-day daily-OHLCV equity backfill</b> that seeds the
 * Minervini 150/200-day moving-average history. For each NSE cash-equity in the universe it resolves
 * the Upstox {@code NSE_EQ|<ISIN>} key ({@link UpstoxEquityMasterClient}), pulls ~200 daily candles
 * ({@link UpstoxExpiredInstrumentsClient#activeCandles} with interval {@code day}), and upserts them
 * into the native {@code candles}@1d hypertable as {@code source='BACKFILL'} (the dense
 * {@code readDailyWithWarmup} path the screener's MA gates read). NO OI (equity), NO migration —
 * {@code candles} already accepts {@code interval='1d'} + {@code source='BACKFILL'}.
 *
 * <p>Mirrors the {@link ExpiredBackfillService} job shape: a 202-style async trigger that acquires a
 * single-run lock SYNCHRONOUSLY (the caller gets 503/409 immediately) then runs on a daemon thread;
 * live progress + a last-run audit surface via {@link #status}. Daily granularity is tiny
 * (~200 rows/symbol) so there is no OOM risk and no cagg refresh — a sequential, throttled pass over
 * the universe is plenty (the per-call Upstox rate limiter paces the CDN).
 */
@Service
public class EquityDailyBackfillService {

  private static final Logger log = LoggerFactory.getLogger(EquityDailyBackfillService.class);

  /** Calendar days back to request so ~200 NSE trading sessions are covered (≈280 incl. weekends/holidays). */
  static final int DEFAULT_DAYS = 300;

  /** The Upstox daily candle interval token. */
  private static final String UPSTOX_INTERVAL = "day";

  /** Per-symbol pacing so a full-universe run never bursts past the shared Upstox rate window. */
  private static final long THROTTLE_MS = 120;

  /** The active-NSE-equity universe (excludes indices + synthetic continuous rows). */
  private static final String UNIVERSE_SQL =
      "SELECT tradingsymbol FROM instruments WHERE is_active AND instrument_type = 'EQ'"
          + " AND exchange = 'NSE' AND segment NOT IN ('INDICES','SYN-CONT') ORDER BY tradingsymbol";

  /** Outcome of one backfill run — coverage + the candle rows written. */
  public record BackfillSummary(
      String jobId, int symbols, int written, int skipped, int failed, long candleRows) {}

  /**
   * Live + last-run audit, surfaced by {@code GET /api/v1/market/admin/equity-daily-backfill/status}.
   * Mirrors the {@link ExpiredBackfillService.Status} state machine (NEVER_RUN→RUNNING→OK|FAILED);
   * {@code written} = symbols with ≥1 candle upserted, {@code skipped} = no Upstox key / no candles.
   */
  public record Status(
      String jobId,
      String state, // NEVER_RUN | RUNNING | OK | FAILED
      Instant startedAt,
      Instant lastRun,
      long durationMs,
      int symbols,
      int written,
      int skipped,
      int failed,
      long candleRows,
      String currentSymbol,
      String error,
      List<String> recentLogs) {

    static Status neverRun() {
      return new Status(null, "NEVER_RUN", null, null, 0, 0, 0, 0, 0, 0L, null, null, List.of());
    }
  }

  /** Mutable per-run progress the daemon thread updates; {@link #snapshot} builds an immutable {@link Status}. */
  private static final class RunProgress {
    private static final int MAX_LOGS = 50;
    private final String jobId;
    private final Instant startedAt;
    private final AtomicInteger symbols = new AtomicInteger();
    private final AtomicInteger written = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong rows = new AtomicLong();
    private volatile String currentSymbol;
    private final Deque<String> logs = new ArrayDeque<>();

    RunProgress(String jobId, Instant startedAt) {
      this.jobId = jobId;
      this.startedAt = startedAt;
    }

    void log(String line) {
      synchronized (logs) {
        logs.addLast(line);
        while (logs.size() > MAX_LOGS) {
          logs.removeFirst();
        }
      }
    }

    private List<String> logsSnapshot() {
      synchronized (logs) {
        return List.copyOf(logs);
      }
    }

    Status snapshot(String state, Instant lastRun, long durationMs, String error) {
      return new Status(
          jobId, state, startedAt, lastRun, durationMs,
          symbols.get(), written.get(), skipped.get(), failed.get(), rows.get(),
          currentSymbol, error, logsSnapshot());
    }
  }

  private final ObjectProvider<UpstoxEquityMasterClient> masterProvider;
  private final ObjectProvider<UpstoxExpiredInstrumentsClient> upstoxProvider;
  private final CandleRepository candles;
  private final JdbcTemplate jdbc;
  /** Run-audit ledger (V030) — nullable in the test seam; every call here is fail-soft. */
  private final BackfillJobRepository jobRepo;
  private final java.time.Clock clock;
  private final long throttleMs;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<Status> status = new AtomicReference<>(Status.neverRun());
  private volatile RunProgress progress;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "equity-daily-backfill");
            t.setDaemon(true);
            return t;
          });

  /** Wires the backfill inputs; the Upstox clients are optional (absent unless analytics is enabled). */
  @org.springframework.beans.factory.annotation.Autowired
  public EquityDailyBackfillService(
      ObjectProvider<UpstoxEquityMasterClient> masterProvider,
      ObjectProvider<UpstoxExpiredInstrumentsClient> upstoxProvider,
      CandleRepository candles,
      JdbcTemplate jdbc,
      BackfillJobRepository jobRepo,
      java.time.Clock clock) {
    this(masterProvider, upstoxProvider, candles, jdbc, jobRepo, clock, THROTTLE_MS);
  }

  /** Test seam: zero/short throttle; no run-audit. */
  EquityDailyBackfillService(
      ObjectProvider<UpstoxEquityMasterClient> masterProvider,
      ObjectProvider<UpstoxExpiredInstrumentsClient> upstoxProvider,
      CandleRepository candles,
      JdbcTemplate jdbc,
      java.time.Clock clock,
      long throttleMs) {
    this(masterProvider, upstoxProvider, candles, jdbc, null, clock, throttleMs);
  }

  private EquityDailyBackfillService(
      ObjectProvider<UpstoxEquityMasterClient> masterProvider,
      ObjectProvider<UpstoxExpiredInstrumentsClient> upstoxProvider,
      CandleRepository candles,
      JdbcTemplate jdbc,
      BackfillJobRepository jobRepo,
      java.time.Clock clock,
      long throttleMs) {
    this.masterProvider = masterProvider;
    this.upstoxProvider = upstoxProvider;
    this.candles = candles;
    this.jdbc = jdbc;
    this.jobRepo = jobRepo;
    this.clock = clock;
    this.throttleMs = throttleMs;
  }

  /**
   * 202-style async trigger: validates the Upstox source + acquires the single-run lock SYNCHRONOUSLY
   * (so the caller gets the 503/409 immediately), then runs on the daemon thread. {@code symbols} null
   * / empty ⇒ the whole active-NSE-equity universe; {@code days} null ⇒ {@value #DEFAULT_DAYS}.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public Map<String, String> triggerAsync(List<String> symbols, Integer days) {
    UpstoxEquityMasterClient master = requireMaster();
    UpstoxExpiredInstrumentsClient upstox = requireUpstox();
    int lookback = days != null && days > 0 ? days : DEFAULT_DAYS;
    List<String> universe = resolveUniverse(symbols);
    if (universe.isEmpty()) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "no NSE equities to backfill (empty universe)");
    }
    if (!running.compareAndSet(false, true)) {
      throw new ApiException(
          409, ErrorCodes.CONFLICT_BACKFILL_RUNNING, "an equity daily backfill is already in progress");
    }
    String jobId = UUID.randomUUID().toString();
    Instant started = Instant.now();
    RunProgress p = new RunProgress(jobId, started);
    progress = p;
    status.set(p.snapshot("RUNNING", null, 0, null));
    long auditId = auditStart(universe, lookback);
    executor.submit(
        () -> {
          try {
            BackfillSummary s = run(master, upstox, universe, lookback, p);
            log.info(
                "equity-daily-backfill {} done: {} symbols, {} written, {} skipped, {} failed, {} rows",
                jobId, s.symbols(), s.written(), s.skipped(), s.failed(), s.candleRows());
            auditComplete(auditId, s.candleRows());
            Instant ended = Instant.now();
            status.set(p.snapshot("OK", ended, Duration.between(started, ended).toMillis(), null));
          } catch (Exception e) {
            log.error("equity-daily-backfill {} failed", jobId, e);
            auditFail(auditId, e.toString());
            Instant ended = Instant.now();
            status.set(
                p.snapshot("FAILED", ended, Duration.between(started, ended).toMillis(), e.toString()));
          } finally {
            running.set(false);
            progress = null;
          }
        });
    return Map.of("jobId", jobId, "status", "started");
  }

  /** Live progress while RUNNING (rebuilt from the in-flight counters), else the last-run audit. */
  public Status status() {
    RunProgress p = progress;
    Status s = status.get();
    return (p != null && "RUNNING".equals(s.state())) ? p.snapshot("RUNNING", null, 0, null) : s;
  }

  /**
   * One backfill pass over the universe: per symbol resolve the Upstox NSE_EQ key, pull the daily
   * candles over {@code [today - days, today]}, and upsert them as {@code 1d}/{@code BACKFILL}. A
   * symbol with no key / no candles is SKIPPED (counted, not failed); an Upstox error is FAILED and the
   * pass continues. Tests drive this via {@link #triggerAsync} + {@link #status} polling.
   */
  private BackfillSummary run(
      UpstoxEquityMasterClient master,
      UpstoxExpiredInstrumentsClient upstox,
      List<String> universe,
      int days,
      RunProgress p) {
    LocalDate to = LocalDate.now(clock.withZone(Ist.ZONE));
    LocalDate from = to.minusDays(days);
    for (String symbol : universe) {
      p.symbols.incrementAndGet();
      p.currentSymbol = symbol;
      String key = master.equityKey(symbol);
      if (key == null) {
        p.skipped.incrementAndGet();
        continue;
      }
      List<Bar> bars;
      try {
        bars = upstox.activeCandles(key, UPSTOX_INTERVAL, from, to);
      } catch (RuntimeException e) {
        p.failed.incrementAndGet();
        p.log("FAIL " + symbol + " (" + key + "): " + e.getMessage());
        continue;
      }
      if (bars.isEmpty()) {
        p.skipped.incrementAndGet();
        continue;
      }
      List<Candle> rows = new ArrayList<>(bars.size());
      for (Bar b : bars) {
        rows.add(toCandle(symbol, b));
      }
      candles.upsertAuthoritativeAll(rows);
      p.written.incrementAndGet();
      p.rows.addAndGet(rows.size());
      p.log(symbol + ": " + rows.size() + " daily candles");
      throttle();
    }
    return new BackfillSummary(
        p.jobId, p.symbols.get(), p.written.get(), p.skipped.get(), p.failed.get(), p.rows.get());
  }

  /**
   * Maps one Upstox daily bar to a native {@code candles}@1d row: NSE cash equity, {@code interval=1d},
   * {@code oi=null} (equity), {@code source=BACKFILL}. The bucket is normalized to IST 00:00 of the
   * trade day (the {@code candles_1d}/{@code readDailyWithWarmup} daily-candle convention) regardless
   * of the offset Upstox stamps.
   */
  static Candle toCandle(String tradingsymbol, Bar b) {
    OffsetDateTime bucket =
        b.bucket().atZoneSameInstant(Ist.ZONE).toLocalDate().atStartOfDay(Ist.ZONE).toOffsetDateTime();
    return new Candle(
        "NSE", tradingsymbol, "1d", bucket,
        b.open(), b.high(), b.low(), b.close(), b.volume(), null, "BACKFILL");
  }

  /** The explicit symbol list (uppercased) or, when none given, the full active-NSE-equity universe. */
  private List<String> resolveUniverse(List<String> symbols) {
    if (symbols != null && !symbols.isEmpty()) {
      return symbols.stream().map(s -> s.trim().toUpperCase()).filter(s -> !s.isEmpty()).distinct().toList();
    }
    return jdbc.queryForList(UNIVERSE_SQL, String.class);
  }

  private void throttle() {
    if (throttleMs <= 0) {
      return;
    }
    try {
      Thread.sleep(throttleMs);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted during equity-backfill throttle", ie);
    }
  }

  /**
   * Opens a {@code backfill_jobs} audit row for this run — best-effort. A logging-DB failure (or a null
   * {@code jobRepo} in the test seam) returns -1 and is swallowed with a warn: the audit must never
   * break the backfill it records.
   */
  private long auditStart(List<String> universe, int days) {
    if (jobRepo == null) {
      return -1;
    }
    try {
      String params = String.format("{\"symbols\":%d,\"days\":%d}", universe.size(), days);
      return jobRepo.start("EQUITY_DAILY", params);
    } catch (RuntimeException e) {
      log.warn("equity-daily-backfill: could not open audit row (non-fatal): {}", e.toString());
      return -1;
    }
  }

  private void auditComplete(long auditId, long rows) {
    if (jobRepo == null || auditId < 0) {
      return;
    }
    try {
      jobRepo.complete(auditId, rows);
    } catch (RuntimeException e) {
      log.warn("equity-daily-backfill: could not close audit row (non-fatal): {}", e.toString());
    }
  }

  private void auditFail(long auditId, String error) {
    if (jobRepo == null || auditId < 0) {
      return;
    }
    try {
      jobRepo.fail(auditId, error);
    } catch (RuntimeException e) {
      log.warn("equity-daily-backfill: could not mark audit row failed (non-fatal): {}", e.toString());
    }
  }

  private UpstoxEquityMasterClient requireMaster() {
    UpstoxEquityMasterClient master = masterProvider.getIfAvailable();
    if (master == null) {
      throw new ApiException(
          503,
          ErrorCodes.NOT_CONFIGURED,
          "Upstox equity instrument-master not configured "
              + "(set artha.upstox.analytics.enabled=true on the live profile)");
    }
    return master;
  }

  private UpstoxExpiredInstrumentsClient requireUpstox() {
    UpstoxExpiredInstrumentsClient upstox = upstoxProvider.getIfAvailable();
    if (upstox == null) {
      throw new ApiException(
          503,
          ErrorCodes.NOT_CONFIGURED,
          "Upstox historical-candle source not configured "
              + "(set artha.upstox.analytics.enabled=true on the live profile)");
    }
    return upstox;
  }
}

package in.arthayantra.marketdata.upstox;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.openalgo.OpenAlgoSymbols;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Leg;
import java.math.BigDecimal;
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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Expired-instruments backfill importer (data-foundation milestone — backtest data). For each
 * (underlying × past expiry) it enumerates the CE/PE chain + future(s) via the Upstox Plus
 * expired-instruments API, then walks each contract's per-minute OHLCV+OI history into the shared
 * {@code candles} hypertable ({@code source='BACKFILL'}, interval {@code 1m}) so a strategy can be
 * backtested on a real traded option/future price series. Each contract is also registered in
 * {@code expired_contracts} so the backtest engine / OI pages can resolve the (expired) tradingsymbol
 * back to its strike/expiry/lot.
 *
 * <p>The symbol written is the canonical OpenAlgo grammar ({@link OpenAlgoSymbols}) — unambiguous
 * across weekly/monthly — and is the SAME key in both {@code candles} and {@code expired_contracts}.
 * The Upstox client bean is present only on the live profile with {@code
 * artha.upstox.analytics.enabled=true}; absent → the trigger 503s as unconfigured. A single run holds
 * an in-process lock (409 on overlap) and executes on a daemon thread (the importer 202s). Re-runs are
 * resumable + idempotent: a contract whose 1m candles already exist is skipped, and the candle upsert
 * merges.
 */
@Service
public class ExpiredBackfillService {

  private static final Logger log = LoggerFactory.getLogger(ExpiredBackfillService.class);

  private static final String BACKFILL = "BACKFILL";
  private static final String CANDLE_INTERVAL = "1m";
  private static final String UPSTOX_INTERVAL = "1minute";
  /** ≤ one month per call (the v2 1-minute window cap); walked back from expiry. */
  private static final int CHUNK_DAYS = 28;
  /** Walk depth by contract type: a weekly is listed ~5–6 weeks out (≤3×28d), a monthly ~3 months. */
  private static final int WEEKLY_MAX_WINDOWS = 3;
  private static final int MONTHLY_MAX_WINDOWS = 4;
  /**
   * Retry-on-empty horizon: only retry an empty window whose start is within this many days of expiry
   * — the tradeable, liquid stretch where a false-empty would lose real data. Far-back windows
   * (pre-listing / least liquid) trust an empty immediately, cutting the bulk of the wasted dead-window
   * calls; the 2-consecutive-empty stop still guards a mid-life illiquidity gap, and {@code force}
   * re-fetches everything if a guaranteed-complete set is ever needed.
   */
  private static final int RETRY_HORIZON_DAYS = 35;
  /**
   * Politeness gap between calls (per worker). 100ms × 2 workers ≈ 8 req/s aggregate — under Upstox's
   * per-token burst limit (the 30ms × 6 earlier tripped 429 UDAPI10005). The client's 429 backoff is
   * the reactive safety net on top of this proactive pacing.
   */
  private static final long THROTTLE_MS = 100;

  /**
   * Per-leg fetch+write ceiling — bounds the {@code Future.get()} below so a hung HTTP retry / saturated
   * limiter / Timescale chunk-lock on the candle write can't park the whole run forever. (The 2026-06-25
   * incident: an UNBOUNDED get parked &gt;25 min on one stuck leg; the run held its lock, so the hourly
   * auto-resume self-heal couldn't recover it.) 180 s is generous — one contract's 1-yr 1m fetch+write,
   * even with retries, is well under it.
   */
  private static final long DEFAULT_LEG_TIMEOUT_SEC = 180;
  /**
   * Concurrent per-contract workers. Kept LOW (2): Upstox throttles a high-volume token by
   * CONCURRENCY, not raw rate — a single direct call is ~0.17s, but 6 parallel streams get throttled
   * to ~2.3s each and eventually slow-trickle until the workers hang. Two polite streams stay fast and
   * stall-free (the 45 req/s cap is never the binding constraint here).
   */
  private static final int CONCURRENCY = 2;
  /**
   * Strike band: keep only options whose strike is within ±20% of the underlying's price RANGE over
   * the contract's life — the actually-tradeable set. The expired roster lists EVERY strike that ever
   * existed as spot roamed (~1020/expiry), almost all deep OTM/ITM with zero volume; bounding cuts the
   * dead tail (the unbounded pull OOM-crashed the live DB — see the data-foundation incident notes).
   */
  private static final BigDecimal BAND_LOW = new BigDecimal("0.80");
  private static final BigDecimal BAND_HIGH = new BigDecimal("1.20");
  /** Lookback for the underlying price range (covers a monthly contract's full life). */
  private static final int INDEX_LOOKBACK_DAYS = 55;

  /** The two indices the owner backfills; an explicit map keeps the Upstox instrument_key authoritative. */
  private static final Map<String, String> UNDERLYING_KEYS =
      Map.of("NIFTY", "NSE_INDEX|Nifty 50", "SENSEX", "BSE_INDEX|SENSEX");

  /** Underlying → the {exchange, tradingsymbol} of its index 1d candles (the strike-band reference). */
  private static final Map<String, String[]> INDEX_SERIES =
      Map.of("NIFTY", new String[] {"NSE", "NIFTY 50"}, "SENSEX", new String[] {"BSE", "SENSEX"});

  /** Which expired legs to fetch: option (CE/PE) legs, future (FUT) legs, or both (the default). */
  public enum ContractType {
    OPTIONS,
    FUTURES,
    BOTH;

    /** Parses the request token case-insensitively; null/blank/unknown ⇒ {@link #BOTH} (no filter). */
    public static ContractType parse(String raw) {
      if (raw == null || raw.isBlank()) {
        return BOTH;
      }
      return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
        case "OPTIONS" -> OPTIONS;
        case "FUTURES" -> FUTURES;
        default -> BOTH;
      };
    }
  }

  /** Outcome of one backfill run — coverage + the candle rows written. */
  public record BackfillSummary(
      String jobId,
      int expiries,
      int contracts,
      int legsWritten,
      int legsSkipped,
      int legsFailed,
      long candleRows) {}

  /**
   * Live + last-run audit, surfaced by {@code GET /api/v1/market/admin/expired-backfill/status} (B1).
   * While {@code RUNNING} the counters + {@code currentExpiry} climb in real time; {@code recentLogs}
   * carries the last per-expiry/error lines for the UI log feed. Mirrors the {@code BhavcopyBackfillService.Status}
   * precedent (state machine NEVER_RUN→RUNNING→OK|FAILED).
   */
  public record Status(
      String jobId,
      String state, // NEVER_RUN | RUNNING | OK | FAILED
      Instant startedAt,
      Instant lastRun,
      long durationMs,
      int expiries,
      int contracts,
      int legsWritten,
      int legsSkipped,
      int legsFailed,
      long candleRows,
      String currentExpiry,
      String error,
      List<String> recentLogs) {

    static Status neverRun() {
      return new Status(
          null, "NEVER_RUN", null, null, 0, 0, 0, 0, 0, 0, 0L, null, null, List.of());
    }
  }

  /** Mutable per-run progress the daemon thread updates; {@link #snapshot} builds an immutable {@link Status}. */
  private static final class RunProgress {
    private static final int MAX_LOGS = 50;
    private final String jobId;
    private final Instant startedAt;
    private final AtomicInteger expiries = new AtomicInteger();
    private final AtomicInteger contracts = new AtomicInteger();
    private final AtomicInteger written = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong rows = new AtomicLong();
    private volatile String currentExpiry;
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
          expiries.get(), contracts.get(), written.get(), skipped.get(), failed.get(), rows.get(),
          currentExpiry, error, logsSnapshot());
    }
  }

  private final ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider;
  private final CandleRepository candles;
  private final ExpiredBackfillRepository repo;
  /** Run-audit ledger (V030) — nullable in the test seams; every call here is fail-soft. */
  private final BackfillJobRepository jobRepo;
  private final long throttleMs;
  private final long legTimeoutSec;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<Status> status = new AtomicReference<>(Status.neverRun());
  private volatile RunProgress progress;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "expired-backfill");
            t.setDaemon(true);
            return t;
          });

  /** Wires the backfill inputs; the Upstox client is optional (absent unless analytics is enabled). */
  @org.springframework.beans.factory.annotation.Autowired
  public ExpiredBackfillService(
      ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider,
      CandleRepository candles,
      ExpiredBackfillRepository repo,
      BackfillJobRepository jobRepo) {
    this(clientProvider, candles, repo, jobRepo, THROTTLE_MS, DEFAULT_LEG_TIMEOUT_SEC);
  }

  /** Test seam: stub client + zero throttle (default per-leg timeout); no run-audit. */
  ExpiredBackfillService(
      ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider,
      CandleRepository candles,
      ExpiredBackfillRepository repo,
      long throttleMs) {
    this(clientProvider, candles, repo, null, throttleMs, DEFAULT_LEG_TIMEOUT_SEC);
  }

  /** Test seam: also override the per-leg fetch timeout (to exercise the stuck-leg guard). */
  ExpiredBackfillService(
      ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider,
      CandleRepository candles,
      ExpiredBackfillRepository repo,
      long throttleMs,
      long legTimeoutSec) {
    this(clientProvider, candles, repo, null, throttleMs, legTimeoutSec);
  }

  private ExpiredBackfillService(
      ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider,
      CandleRepository candles,
      ExpiredBackfillRepository repo,
      BackfillJobRepository jobRepo,
      long throttleMs,
      long legTimeoutSec) {
    this.clientProvider = clientProvider;
    this.candles = candles;
    this.repo = repo;
    this.jobRepo = jobRepo;
    this.throttleMs = throttleMs;
    this.legTimeoutSec = legTimeoutSec;
  }

  /**
   * 202-style async trigger: validates the source + acquires the single-run lock SYNCHRONOUSLY (so the
   * caller gets the 503/409 immediately), then runs the backfill on the daemon thread. {@code
   * contractType} filters which legs are fetched (defaults to {@link ContractType#BOTH} — existing
   * callers stay byte-identical).
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public Map<String, String> triggerAsync(
      List<String> underlyings,
      LocalDate from,
      LocalDate to,
      String interval,
      boolean force,
      ContractType contractType) {
    UpstoxExpiredInstrumentsClient client = requireClient();
    if (interval != null && !UPSTOX_INTERVAL.equals(interval)) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "only the 1minute interval is supported in this build");
    }
    if (!running.compareAndSet(false, true)) {
      throw new ApiException(
          409, ErrorCodes.CONFLICT_BACKFILL_RUNNING, "an expired backfill is already in progress");
    }
    ContractType type = contractType == null ? ContractType.BOTH : contractType;
    String jobId = UUID.randomUUID().toString();
    Instant started = Instant.now();
    RunProgress p = new RunProgress(jobId, started);
    progress = p;
    status.set(p.snapshot("RUNNING", null, 0, null));
    long auditId = auditStart(underlyings, from, to, force, type);
    executor.submit(
        () -> {
          try {
            BackfillSummary s = run(client, underlyings, from, to, jobId, force, type);
            log.info(
                "expired-backfill {} done: {} expiries, {} contracts, {} written, {} skipped, {} failed, {} rows",
                jobId, s.expiries(), s.contracts(),
                s.legsWritten(), s.legsSkipped(), s.legsFailed(), s.candleRows());
            auditComplete(auditId, s.candleRows());
            Instant ended = Instant.now();
            status.set(
                p.snapshot("OK", ended, java.time.Duration.between(started, ended).toMillis(), null));
          } catch (Exception e) {
            log.error("expired-backfill {} failed", jobId, e);
            auditFail(auditId, e.toString());
            Instant ended = Instant.now();
            status.set(
                p.snapshot(
                    "FAILED", ended, java.time.Duration.between(started, ended).toMillis(), e.toString()));
          } finally {
            running.set(false);
            progress = null;
          }
        });
    return Map.of("jobId", jobId, "status", "started");
  }

  /** Live progress while RUNNING (rebuilt from the in-flight counters), else the last-run audit (B1). */
  public Status status() {
    RunProgress p = progress;
    Status s = status.get();
    return (p != null && "RUNNING".equals(s.state())) ? p.snapshot("RUNNING", null, 0, null) : s;
  }

  /**
   * One backfill pass over (underlyings × expiries in [from, to]). Per expiry the strike-bounded
   * contracts are imported by a bounded worker pool ({@value #CONCURRENCY}); each expiry barriers
   * before the next so memory stays bounded. The continuous-aggregate refresh is deliberately NOT run
   * here (it materialized 5 caggs over the whole span and OOM-crashed the live DB; backtest reads 1m
   * directly — a higher-timeframe refresh, if ever needed, is a separate throttled admin op). Public
   * for tests.
   */
  public BackfillSummary run(
      UpstoxExpiredInstrumentsClient client,
      List<String> underlyings,
      LocalDate from,
      LocalDate to,
      String jobId,
      boolean force,
      ContractType contractType) {
    ContractType type = contractType == null ? ContractType.BOTH : contractType;
    RunProgress p =
        (progress != null && jobId.equals(progress.jobId))
            ? progress
            : new RunProgress(jobId, Instant.now());
    AtomicInteger expiries = p.expiries;
    AtomicInteger contracts = p.contracts;
    AtomicInteger written = p.written;
    AtomicInteger skipped = p.skipped;
    AtomicInteger failed = p.failed;
    AtomicLong rows = p.rows;

    ExecutorService pool =
        Executors.newFixedThreadPool(
            CONCURRENCY,
            r -> {
              Thread t = new Thread(r, "expired-backfill-leg");
              t.setDaemon(true);
              return t;
            });
    try {
      for (String underlying : underlyings) {
        String underlyingKey = UNDERLYING_KEYS.get(underlying.toUpperCase());
        if (underlyingKey == null) {
          throw new ApiException(
              400, ErrorCodes.VALIDATION_FAILED, "unknown underlying '" + underlying + "' (NIFTY, SENSEX)");
        }
        String[] indexRef = INDEX_SERIES.get(underlying.toUpperCase());
        for (LocalDate expiry : client.expiries(underlyingKey)) {
          if (expiry.isBefore(from) || expiry.isAfter(to)) {
            continue;
          }
          expiries.incrementAndGet();
          p.currentExpiry = underlying + " " + expiry;
          List<Leg> legs = new ArrayList<>();
          try {
            legs.addAll(client.optionContracts(underlyingKey, expiry));
            legs.addAll(client.futureContracts(underlyingKey, expiry));
          } catch (ResourceAccessException | HttpServerErrorException e) {
            // Transient network/5xx on the per-expiry contract enumeration: skip THIS expiry and
            // continue the run instead of aborting hours of in-flight work. The run is resumable +
            // idempotent, so the skipped expiry is picked up on the next pass. (A single
            // UnknownHostException here killed the whole 2026-06-24 run — see incident notes.)
            failed.incrementAndGet();
            log.warn(
                "expired-backfill {}: expiry {} {} SKIPPED (transient): {}",
                jobId, underlying, expiry, e.toString());
            p.log(underlying + " " + expiry + " SKIPPED (transient): " + e.getMessage());
            continue;
          }
          List<Leg> bounded = filterType(boundStrikes(legs, indexRef, expiry), type);
          contracts.addAndGet(bounded.size());

          List<Future<?>> inflight = new ArrayList<>(bounded.size());
          for (Leg leg : bounded) {
            inflight.add(
                pool.submit(
                    () -> {
                      try {
                        int n = backfillLeg(client, underlying, leg, force);
                        if (n < 0) {
                          skipped.incrementAndGet();
                        } else {
                          written.incrementAndGet();
                          rows.addAndGet(n);
                        }
                      } catch (RuntimeException e) {
                        failed.incrementAndGet();
                        log.warn(
                            "expired-backfill {}: leg {} {} failed: {}",
                            jobId, underlying, leg.instrumentKey(), e.toString());
                      }
                    }));
          }
          for (Future<?> f : inflight) {
            try {
              f.get(legTimeoutSec, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("expired-backfill interrupted", ie);
            } catch (java.util.concurrent.ExecutionException ee) {
              failed.incrementAndGet();
            } catch (java.util.concurrent.TimeoutException te) {
              // One leg's fetch+write exceeded the ceiling (hung HTTP retry / saturated limiter /
              // Timescale chunk-lock on the candle write). Cancel + count failed so the run keeps moving
              // instead of parking forever; the next coverage-aware resume retries the leg.
              f.cancel(true);
              failed.incrementAndGet();
              log.warn(
                  "expired-backfill {}: a leg fetch exceeded {}s — cancelled + skipped (stuck fetch/write)",
                  jobId, legTimeoutSec);
            }
          }
          log.info(
              "expired-backfill {}: {} {} → {} contracts in band ({} total registered)",
              jobId, underlying, expiry, bounded.size(), written.get() + skipped.get());
          p.log(underlying + " " + expiry + " → " + bounded.size() + " contracts in band");
        }
      }
    } finally {
      pool.shutdown();
    }
    return new BackfillSummary(
        jobId, expiries.get(), contracts.get(), written.get(), skipped.get(), failed.get(), rows.get());
  }

  /**
   * Keeps only option legs whose strike is within ±20% of the underlying's price RANGE over the
   * contract's life; futures (no strike) always pass. Falls back to the FULL roster (logged) if no
   * index range is available so a missing reference over-fetches rather than silently drops everything.
   */
  private List<Leg> boundStrikes(List<Leg> legs, String[] indexRef, LocalDate expiry) {
    if (indexRef == null) {
      return legs;
    }
    BigDecimal[] range =
        repo.indexRange(indexRef[0], indexRef[1], expiry.minusDays(INDEX_LOOKBACK_DAYS), expiry.plusDays(1));
    if (range == null) {
      log.warn("expired-backfill: no index range for {} {} — keeping all strikes", indexRef[1], expiry);
      return legs;
    }
    BigDecimal lo = range[0].multiply(BAND_LOW);
    BigDecimal hi = range[1].multiply(BAND_HIGH);
    List<Leg> kept = new ArrayList<>(legs.size());
    for (Leg leg : legs) {
      if ("FUT".equals(leg.instrumentType())) {
        kept.add(leg);
        continue;
      }
      BigDecimal strike = leg.strike();
      if (strike != null && strike.compareTo(lo) >= 0 && strike.compareTo(hi) <= 0) {
        kept.add(leg);
      }
    }
    return kept;
  }

  /**
   * Keeps only the leg types the operator asked for. {@link ContractType#BOTH} (the default) is a
   * no-op, so an unfiltered run is byte-identical to the pre-selector behaviour. Options = CE/PE,
   * futures = FUT (the {@code instrument_type} the Upstox roster carries).
   */
  private static List<Leg> filterType(List<Leg> legs, ContractType type) {
    if (type == ContractType.BOTH) {
      return legs;
    }
    boolean wantFut = type == ContractType.FUTURES;
    List<Leg> kept = new ArrayList<>(legs.size());
    for (Leg leg : legs) {
      if ("FUT".equals(leg.instrumentType()) == wantFut) {
        kept.add(leg);
      }
    }
    return kept;
  }

  /**
   * Backfills one contract's 1m candles + registers it with coverage. Returns rows written, or -1 if
   * the contract is already COMPLETE (and not a {@code force} pass). A registered-but-short (PARTIAL)
   * contract IS re-fetched — that closes a hole left by a transient Upstox false-empty — but is then
   * marked complete so re-fetches are bounded to ~2 passes; {@code force} re-fetches everything.
   */
  private int backfillLeg(
      UpstoxExpiredInstrumentsClient client, String underlying, Leg leg, boolean force) {
    String exchange = exchangeOf(leg.segment());
    String base = leg.underlyingSymbol() != null ? leg.underlyingSymbol() : underlying.toUpperCase();
    String symbol = symbolOf(base, leg);

    ExpiredBackfillRepository.Coverage cov = repo.coverage(exchange, symbol);
    if (!force && cov == ExpiredBackfillRepository.Coverage.COMPLETE) {
      return -1; // already fetched through to the liquid expiry tail (or accepted after a re-fetch)
    }

    List<Bar> bars = fetchLife(client, leg.instrumentKey(), leg.expiry(), leg.weekly());
    OffsetDateTime first = null;
    OffsetDateTime last = null;
    List<Candle> candleRows = new ArrayList<>(bars.size());
    for (Bar b : bars) {
      if (first == null || b.bucket().isBefore(first)) {
        first = b.bucket();
      }
      if (last == null || b.bucket().isAfter(last)) {
        last = b.bucket();
      }
      candleRows.add(
          new Candle(
              exchange, symbol, CANDLE_INTERVAL, b.bucket(),
              b.open(), b.high(), b.low(), b.close(), b.volume(), b.oi(), BACKFILL));
    }
    if (!candleRows.isEmpty()) {
      candles.upsertAuthoritativeAll(candleRows);
    }
    // Complete iff the data reaches the liquid expiry tail, OR this was already a re-attempt of a short
    // contract (cov != NONE) — accept it then so a genuinely-short or twice-empty leg can't re-fetch forever.
    boolean reachedExpiry = last != null && !last.toLocalDate().isBefore(leg.expiry().minusDays(1));
    boolean complete = reachedExpiry || cov != ExpiredBackfillRepository.Coverage.NONE;
    repo.upsertContract(
        exchange, symbol, leg.instrumentType(), base, leg.expiry(), leg.strike(),
        leg.lotSize(), leg.tickSize(), leg.weekly(), leg.instrumentKey(), leg.underlyingKey(),
        first, last, candleRows.size(), complete);
    return candleRows.size();
  }

  /**
   * Walks a contract's history newest→oldest in {@value #CHUNK_DAYS}-day windows, stopping after TWO
   * consecutive empty windows once data has been seen (the mid-life-illiquidity-gap guard), or at the
   * type-bounded depth ({@value #WEEKLY_MAX_WINDOWS} for a weekly, {@value #MONTHLY_MAX_WINDOWS} for a
   * monthly). A 28-day window always spans ~18–20 sessions, so weekends/holidays never empty one — an
   * empty window means no-trade/pre-listing. Only NEAR-expiry empties are retried (see windowCandles).
   */
  private List<Bar> fetchLife(
      UpstoxExpiredInstrumentsClient client, String expiredKey, LocalDate expiry, boolean weekly) {
    int maxWindows = weekly ? WEEKLY_MAX_WINDOWS : MONTHLY_MAX_WINDOWS;
    LocalDate retryFloor = expiry.minusDays(RETRY_HORIZON_DAYS);
    List<Bar> all = new ArrayList<>();
    LocalDate windowTo = expiry;
    boolean sawData = false;
    int consecutiveEmpty = 0;
    for (int w = 0; w < maxWindows; w++) {
      LocalDate windowFrom = windowTo.minusDays(CHUNK_DAYS - 1L);
      boolean retryOnEmpty = !windowFrom.isBefore(retryFloor);
      List<Bar> bars = windowCandles(client, expiredKey, windowFrom, windowTo, retryOnEmpty);
      if (bars.isEmpty()) {
        consecutiveEmpty++;
        if (sawData && consecutiveEmpty >= 2) {
          break;
        }
      } else {
        sawData = true;
        consecutiveEmpty = 0;
        all.addAll(bars);
      }
      windowTo = windowFrom.minusDays(1);
    }
    return all;
  }

  /**
   * One window's candles. A NEAR-expiry empty is retried ONCE — a transient Upstox false-empty for a
   * tradeable window is unlikely to repeat, so the retry distinguishes "past listing" from "glitch"
   * and stops a false-empty silently truncating the liquid stretch. A far-back empty ({@code
   * retryOnEmpty=false}) is trusted immediately — it's pre-listing / least-liquid, so the retry there
   * is pure wasted quota. A hard error propagates (caught per-leg → the leg is retried next run).
   */
  private List<Bar> windowCandles(
      UpstoxExpiredInstrumentsClient client,
      String expiredKey,
      LocalDate from,
      LocalDate to,
      boolean retryOnEmpty) {
    List<Bar> bars = client.candles(expiredKey, UPSTOX_INTERVAL, from, to);
    throttle();
    if (bars.isEmpty() && retryOnEmpty) {
      bars = client.candles(expiredKey, UPSTOX_INTERVAL, from, to);
      throttle();
    }
    return bars;
  }

  private static String symbolOf(String base, Leg leg) {
    if ("FUT".equals(leg.instrumentType())) {
      return OpenAlgoSymbols.futureSymbol(base, leg.expiry());
    }
    return OpenAlgoSymbols.optionSymbol(base, leg.expiry(), leg.strike(), leg.instrumentType());
  }

  private static String exchangeOf(String segment) {
    return switch (segment == null ? "" : segment) {
      case "NSE_FO" -> "NFO";
      case "BSE_FO" -> "BFO";
      // Fail loud rather than silently mislabel an unexpected segment (e.g. a BSE contract under NFO).
      default -> throw new IllegalStateException("unexpected Upstox segment: " + segment);
    };
  }

  private void throttle() {
    if (throttleMs <= 0) {
      return;
    }
    try {
      Thread.sleep(throttleMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Opens a {@code backfill_jobs} audit row for this run — best-effort. A logging-DB failure (or a null
   * {@code jobRepo} in the test seams) returns -1 and is swallowed with a warn: the audit must never
   * break the backfill it records.
   */
  private long auditStart(
      List<String> underlyings, LocalDate from, LocalDate to, boolean force, ContractType type) {
    if (jobRepo == null) {
      return -1;
    }
    try {
      String params =
          String.format(
              "{\"underlyings\":%s,\"from\":%s,\"to\":%s,\"force\":%s,\"contractType\":\"%s\"}",
              jsonArray(underlyings), jsonOrNull(from), jsonOrNull(to), force, type);
      return jobRepo.start("EXPIRED", params);
    } catch (RuntimeException e) {
      log.warn("expired-backfill: could not open audit row (non-fatal): {}", e.toString());
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
      log.warn("expired-backfill: could not close audit row (non-fatal): {}", e.toString());
    }
  }

  private void auditFail(long auditId, String error) {
    if (jobRepo == null || auditId < 0) {
      return;
    }
    try {
      jobRepo.fail(auditId, error);
    } catch (RuntimeException e) {
      log.warn("expired-backfill: could not mark audit row failed (non-fatal): {}", e.toString());
    }
  }

  private static String jsonArray(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "null";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
    }
    return sb.append(']').toString();
  }

  private static String jsonOrNull(LocalDate date) {
    return date == null ? "null" : "\"" + date + "\"";
  }

  private UpstoxExpiredInstrumentsClient requireClient() {
    UpstoxExpiredInstrumentsClient client = clientProvider.getIfAvailable();
    if (client == null) {
      throw new ApiException(
          503,
          ErrorCodes.NOT_CONFIGURED,
          "Upstox expired-instruments source not configured "
              + "(set artha.upstox.analytics.enabled=true on the live profile)");
    }
    return client;
  }
}

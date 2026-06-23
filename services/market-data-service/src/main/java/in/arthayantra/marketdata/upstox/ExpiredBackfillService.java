package in.arthayantra.marketdata.upstox;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.openalgo.live.OpenAlgoSymbols;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Leg;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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
  /** Up to ~112 days back — covers a 3-month monthly contract; weeklies stop early when empty. */
  private static final int MAX_WINDOWS = 4;
  /** Politeness gap between candle calls (≈33 req/s, well under the 45 req/s cap). */
  private static final long THROTTLE_MS = 30;

  /** The two indices the owner backfills; an explicit map keeps the Upstox instrument_key authoritative. */
  private static final Map<String, String> UNDERLYING_KEYS =
      Map.of("NIFTY", "NSE_INDEX|Nifty 50", "SENSEX", "BSE_INDEX|SENSEX");

  /** Outcome of one backfill run — coverage + the candle rows written. */
  public record BackfillSummary(
      String jobId,
      int expiries,
      int contracts,
      int legsWritten,
      int legsSkipped,
      int legsFailed,
      long candleRows) {}

  private final ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider;
  private final CandleRepository candles;
  private final ExpiredBackfillRepository repo;
  private final long throttleMs;
  private final AtomicBoolean running = new AtomicBoolean(false);
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
      ExpiredBackfillRepository repo) {
    this(clientProvider, candles, repo, THROTTLE_MS);
  }

  /** Test seam: lets the unit test pass a stub client + zero throttle. */
  ExpiredBackfillService(
      ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider,
      CandleRepository candles,
      ExpiredBackfillRepository repo,
      long throttleMs) {
    this.clientProvider = clientProvider;
    this.candles = candles;
    this.repo = repo;
    this.throttleMs = throttleMs;
  }

  /**
   * 202-style async trigger: validates the source + acquires the single-run lock SYNCHRONOUSLY (so the
   * caller gets the 503/409 immediately), then runs the backfill on the daemon thread.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public Map<String, String> triggerAsync(
      List<String> underlyings, LocalDate from, LocalDate to, String interval) {
    UpstoxExpiredInstrumentsClient client = requireClient();
    if (interval != null && !UPSTOX_INTERVAL.equals(interval)) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "only the 1minute interval is supported in this build");
    }
    if (!running.compareAndSet(false, true)) {
      throw new ApiException(
          409, ErrorCodes.CONFLICT_BACKFILL_RUNNING, "an expired backfill is already in progress");
    }
    String jobId = UUID.randomUUID().toString();
    executor.submit(
        () -> {
          try {
            BackfillSummary s = run(client, underlyings, from, to, jobId);
            log.info(
                "expired-backfill {} done: {} expiries, {} contracts, {} written, {} skipped, {} failed, {} rows",
                jobId, s.expiries(), s.contracts(),
                s.legsWritten(), s.legsSkipped(), s.legsFailed(), s.candleRows());
          } catch (Exception e) {
            log.error("expired-backfill {} failed", jobId, e);
          } finally {
            running.set(false);
          }
        });
    return Map.of("jobId", jobId, "status", "started");
  }

  /** One synchronous backfill pass over (underlyings × expiries in [from, to]). Public for tests. */
  public BackfillSummary run(
      UpstoxExpiredInstrumentsClient client,
      List<String> underlyings,
      LocalDate from,
      LocalDate to,
      String jobId) {
    int expiries = 0;
    int contracts = 0;
    int written = 0;
    int skipped = 0;
    int failed = 0;
    long rows = 0;

    for (String underlying : underlyings) {
      String underlyingKey = UNDERLYING_KEYS.get(underlying.toUpperCase());
      if (underlyingKey == null) {
        throw new ApiException(
            400, ErrorCodes.VALIDATION_FAILED, "unknown underlying '" + underlying + "' (NIFTY, SENSEX)");
      }
      for (LocalDate expiry : client.expiries(underlyingKey)) {
        if (expiry.isBefore(from) || expiry.isAfter(to)) {
          continue;
        }
        expiries++;
        List<Leg> legs = new ArrayList<>();
        legs.addAll(client.optionContracts(underlyingKey, expiry));
        legs.addAll(client.futureContracts(underlyingKey, expiry));
        for (Leg leg : legs) {
          contracts++;
          try {
            int n = backfillLeg(client, underlying, leg);
            if (n < 0) {
              skipped++;
            } else {
              written++;
              rows += n;
            }
          } catch (RuntimeException e) {
            failed++;
            log.warn("expired-backfill {}: leg {} {} failed: {}", jobId, underlying, leg.instrumentKey(), e.toString());
          }
        }
      }
    }
    if (rows > 0) {
      refreshAggregates(from, to);
    }
    return new BackfillSummary(jobId, expiries, contracts, written, skipped, failed, rows);
  }

  /**
   * Materializes the 5m/15m/1h/1d/1w continuous aggregates over the backfilled span — backfilled 1m
   * bars are inserted deep behind every cagg watermark, so without this they are invisible to every
   * non-1m read (the backtest reads 1m directly, but chart overlays + OI pages hit the caggs).
   * Best-effort: the 1m data backtest needs is already committed, so a refresh hiccup never fails the run.
   */
  private void refreshAggregates(LocalDate from, LocalDate to) {
    try {
      OffsetDateTime start =
          from.minusDays((long) MAX_WINDOWS * CHUNK_DAYS).atStartOfDay().atOffset(Ist.OFFSET);
      OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
      candles.refreshDerivedAggregates(start, end);
    } catch (RuntimeException e) {
      log.warn("expired-backfill aggregate refresh failed (1m data is written): {}", e.toString());
    }
  }

  /** Backfills one contract's 1m candles + registers it. Returns rows written, or -1 if already covered. */
  private int backfillLeg(UpstoxExpiredInstrumentsClient client, String underlying, Leg leg) {
    String exchange = exchangeOf(leg.segment());
    String base = leg.underlyingSymbol() != null ? leg.underlyingSymbol() : underlying.toUpperCase();
    String symbol = symbolOf(base, leg);

    if (repo.isRegistered(exchange, symbol)) {
      return -1; // a prior run already imported + registered this contract
    }

    List<Bar> bars = fetchLife(client, leg.instrumentKey(), leg.expiry());
    List<Candle> candleRows = new ArrayList<>(bars.size());
    for (Bar b : bars) {
      candleRows.add(
          new Candle(
              exchange, symbol, CANDLE_INTERVAL, b.bucket(),
              b.open(), b.high(), b.low(), b.close(), b.volume(), b.oi(), BACKFILL));
    }
    if (!candleRows.isEmpty()) {
      candles.upsertAll(candleRows);
    }
    repo.upsertContract(
        exchange, symbol, leg.instrumentType(), base, leg.expiry(), leg.strike(),
        leg.lotSize(), leg.tickSize(), leg.weekly(), leg.instrumentKey(), leg.underlyingKey());
    return candleRows.size();
  }

  /**
   * Walks a contract's history newest→oldest in {@value #CHUNK_DAYS}-day windows, stopping after TWO
   * consecutive empty windows once data has been seen (i.e. we've passed the listing date) — so a
   * short-lived weekly costs 1–2 calls while a 3-month monthly walks back to {@value #MAX_WINDOWS}.
   * Two-in-a-row (not one) tolerates a mid-life no-trade gap window without truncating earlier bars.
   */
  private List<Bar> fetchLife(
      UpstoxExpiredInstrumentsClient client, String expiredKey, LocalDate expiry) {
    List<Bar> all = new ArrayList<>();
    LocalDate windowTo = expiry;
    boolean sawData = false;
    int consecutiveEmpty = 0;
    for (int w = 0; w < MAX_WINDOWS; w++) {
      LocalDate windowFrom = windowTo.minusDays(CHUNK_DAYS - 1L);
      List<Bar> bars = client.candles(expiredKey, UPSTOX_INTERVAL, windowFrom, windowTo);
      throttle();
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

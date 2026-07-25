package in.arthayantra.marketdata.candles;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cache-first OHLCV (Flow 3 / B-4): coverage check → serve, or fetch the missing sub-ranges in ≤
 * 60-day pages through the rate-limited gateway → upsert → re-read. Mid intervals (5m/15m/1h)
 * resolve from continuous aggregates after ensuring 1m coverage; 1d has its own fetch lineage;
 * 1w reads {@code candles_1w} and is NEVER fetched (B-21). When the breaker is open or a fetch
 * fails, cached partials are served with {@code stale: true} (B-3 degradation ladder).
 */
@Service
public class CandleQueryService {

  private static final Logger log = LoggerFactory.getLogger(CandleQueryService.class);
  // 3m has NO materialised cagg (it is read-time rolled from 1m — see CandleRepository) so the scalper
  // 3m PRIMARY can be served on the live path without an OOM-prone candles_3m refresh.
  private static final List<String> INTERVALS = List.of("1m", "3m", "5m", "15m", "1h", "1d", "1w");

  /** Read result with staleness markers (B-3 ladder step 3). */
  public record CandleRead(List<Candle> items, boolean stale, OffsetDateTime asOf) {}

  private final CandleRepository repository;
  private final HistoricalCandleGateway gateway;
  private final GapDetector gapDetector;
  private final Clock clock;
  private final String fetchSource;
  private final Counter cacheHits;
  private final Counter cacheMisses;
  private final ExecutorService refreshExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "candle-refresh");
            t.setDaemon(true);
            return t;
          });

  /** Wires the cache-first read path. */
  public CandleQueryService(
      CandleRepository repository,
      HistoricalCandleGateway gateway,
      GapDetector gapDetector,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.gateway = gateway;
    this.gapDetector = gapDetector;
    this.clock = clock;
    // provenance from the gateway impl, not the Spring profile — an OpenAlgo-routed fetch
    // must never persist as KITE (artha.marketdata.source.candles selects the impl)
    this.fetchSource = gateway.sourceLabel();
    this.cacheHits = meterRegistry.counter("ay_candle_cache_requests_total", "result", "hit");
    this.cacheMisses = meterRegistry.counter("ay_candle_cache_requests_total", "result", "miss");
    meterRegistry.gauge(
        "ay_candle_cache_hit_ratio",
        this,
        s -> {
          double hits = s.cacheHits.count();
          double total = hits + s.cacheMisses.count();
          return total == 0 ? 1.0 : hits / total;
        });
  }

  /** The cache-first read. */
  public CandleRead read(
      String exchange, String tradingsymbol, String interval, OffsetDateTime from, OffsetDateTime to) {
    if (!INTERVALS.contains(interval)) {
      throw new ApiException(
          400,
          in.arthayantra.common.web.error.ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED,
          "interval must be one of " + INTERVALS);
    }
    boolean stale = false;
    // 1w is only ever ROLLED from cached data, never fetched (B-21) — no coverage pass;
    // CONT synthetic series are stitched locally and must NEVER reach a Kite port (B-19)
    if (!interval.equals("1w") && !tradingsymbol.endsWith("-FUT-CONT")) {
      String baseInterval = interval.equals("1d") ? "1d" : "1m";
      try {
        ensureCoverage(exchange, tradingsymbol, baseInterval, from, to);
      } catch (Exception fetchFailure) {
        log.warn(
            "gap fetch failed for {}:{} {} — serving cached data stale: {}",
            exchange, tradingsymbol, interval, fetchFailure.getMessage());
        stale = true;
      }
    }
    List<Candle> items =
        switch (interval) {
          case "1m", "1d" -> repository.range(exchange, tradingsymbol, interval, from, to);
          // 3m is read-time rolled from the 1m base (no candles_3m cagg) — the scalper PRIMARY.
          case "3m" -> repository.rangeRolledFromOneMinute(exchange, tradingsymbol, 3, from, to);
          case "1w" -> repository.rangeFromAggregate("candles_1w", exchange, tradingsymbol, from, to);
          default ->
              repository.rangeFromAggregate(
                  "candles_" + interval, exchange, tradingsymbol, from, to);
        };
    return new CandleRead(items, stale, OffsetDateTime.now(clock));
  }

  /** Coverage check + gap fetch for a base interval (1m or 1d), refreshing derived aggregates. */
  void ensureCoverage(
      String exchange, String tradingsymbol, String baseInterval, OffsetDateTime from, OffsetDateTime to) {
    ensureCoverage(exchange, tradingsymbol, baseInterval, from, to, true);
  }

  /**
   * Coverage check + gap fetch that can DEFER the derived-aggregate refresh to the caller. The
   * corporate-action rebuild (A14) fetches the base 1m WITHOUT the bundled refresh so it can
   * checkpoint the symbol at {@code BASE_REBUILT} before issuing the (chunked) refresh separately —
   * a hard crash mid-refresh then RESUMES instead of re-purging ~12 years.
   */
  void ensureCoverage(
      String exchange,
      String tradingsymbol,
      String baseInterval,
      OffsetDateTime from,
      OffsetDateTime to,
      boolean refreshAggregates) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime capped = to.isAfter(now) ? now : to;
    if (!from.isBefore(capped)) {
      return;
    }
    List<OffsetDateTime> present =
        repository.presentBuckets(exchange, tradingsymbol, baseInterval, from, capped);
    List<GapDetector.Gap> gaps = gapDetector.gaps(baseInterval, from, capped, present, now);
    if (gaps.isEmpty()) {
      cacheHits.increment();
      return;
    }
    cacheMisses.increment();
    InstrumentKey key = new InstrumentKey(exchange, tradingsymbol);
    boolean backfilled = false;
    for (GapDetector.Gap gap : gaps) {
      for (GapDetector.Gap page : GapDetector.pages(gap)) {
        List<HistoricalCandleGateway.Candle> fetched =
            gateway.fetch(key, baseInterval, page.from().toInstant(), page.to().toInstant());
        if (!fetched.isEmpty()) {
          repository.upsertAuthoritativeAll(
              fetched.stream()
                  .map(
                      c ->
                          new Candle(
                              exchange, tradingsymbol, baseInterval, c.bucketStart(),
                              c.open(), c.high(), c.low(), c.close(), c.volume(), c.oi(),
                              fetchSource))
                  .toList());
          backfilled = true;
        }
      }
    }
    if (backfilled && baseInterval.equals("1m") && refreshAggregates) {
      // backfilled history behind a cagg watermark is invisible until explicitly refreshed
      repository.refreshDerivedAggregates(gaps.get(0).from(), gaps.get(gaps.size() - 1).to());
    }
  }

  /** 202-style forced re-fetch — async on the service executor, never a blocking request. */
  @SuppressWarnings("FutureReturnValueIgnored")
  public Map<String, String> refreshAsync(
      String exchange, String tradingsymbol, String interval, OffsetDateTime from, OffsetDateTime to) {
    if (interval.equals("1w") || interval.equals("3m") || !INTERVALS.contains(interval)) {
      throw new ApiException(
          400,
          in.arthayantra.common.web.error.ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED,
          "1w/3m are rolled locally, never fetched (B-21); refresh 1m or 1d instead");
    }
    if (tradingsymbol.endsWith("-FUT-CONT")) {
      // the stitch is local arithmetic — a CONT symbol must NEVER reach a Kite port (B-19)
      throw new ApiException(
          400,
          in.arthayantra.common.web.error.ErrorCodes.VALIDATION_FAILED,
          "CONT series are stitched locally and cannot be refreshed from Kite");
    }
    String jobId = UUID.randomUUID().toString();
    String baseInterval = interval.equals("1d") ? "1d" : "1m";
    refreshExecutor.submit(
        () -> {
          try {
            // forced refresh ignores present coverage: fetch the whole range
            fetchAndStore(exchange, tradingsymbol, baseInterval, from, to, fetchSource);
            log.info("candle refresh {} done for {}:{}", jobId, exchange, tradingsymbol);
          } catch (Exception e) {
            log.error("candle refresh {} failed", jobId, e);
          }
        });
    return Map.of("jobId", jobId);
  }

  /**
   * Gap-aware coverage pass for the Phase-15A EOD backfill: fetches only the buckets the cache
   * misses at {@code baseInterval} — synchronous, caller owns scheduling.
   */
  public void prefetch(
      String exchange, String tradingsymbol, String baseInterval, OffsetDateTime from, OffsetDateTime to) {
    prefetch(exchange, tradingsymbol, baseInterval, from, to, true);
  }

  /**
   * {@link #prefetch(String, String, String, OffsetDateTime, OffsetDateTime)} that can DEFER the
   * derived-aggregate refresh to the caller — the A14 corporate-action rebuild fetches the base 1m
   * without the bundled refresh so it can checkpoint BASE_REBUILT before the (chunked) refresh.
   */
  public void prefetch(
      String exchange,
      String tradingsymbol,
      String baseInterval,
      OffsetDateTime from,
      OffsetDateTime to,
      boolean refreshAggregates) {
    ensureCoverage(exchange, tradingsymbol, baseInterval, from, to, refreshAggregates);
  }

  /**
   * Phase-13 gap backfill: re-fetch 1m bars for a silent-through-reconnect window, async on the
   * refresh executor, stored with {@code source='BACKFILL'} (B-6 — replayed vs streamed bars).
   *
   * <p>T19 (2026-07-25): {@code LiveTickerFeed} hands the RAW tick-gap instants (last tick seen,
   * e.g. {@code 12:51:38}), and a sub-minute {@code from} produced bars keyed OFF the 1m grid —
   * distinct phantom PK rows the repair never replaced, double-counted by every {@code time_bucket}
   * rollup (887/403/308 rows on 2026-07-15/20/22, 23k historical). This is the only entry that
   * bypasses {@link GapDetector}'s aligned bucket grid, so the window is snapped HERE: floor
   * {@code from}, ceil {@code to} — the superset is safe ({@code upsertAuthoritativeAll} replaces
   * value-changed bars only) and the grid is what the rollups and the recency re-fetch assume.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void backfillRange(
      String exchange, String tradingsymbol, OffsetDateTime rawFrom, OffsetDateTime rawTo) {
    GapDetector.Gap window = alignBackfillWindow(rawFrom, rawTo);
    refreshExecutor.submit(
        () -> {
          try {
            fetchAndStore(exchange, tradingsymbol, "1m", window.from(), window.to(), "BACKFILL");
            log.info(
                "gap backfill done for {}:{} [{}, {})",
                exchange, tradingsymbol, window.from(), window.to());
          } catch (Exception e) {
            log.error("gap backfill failed for {}:{}", exchange, tradingsymbol, e);
          }
        });
  }

  /** T19: floor {@code from} / ceil {@code to} onto the 1m grid (already-aligned bounds pass through). */
  static GapDetector.Gap alignBackfillWindow(OffsetDateTime rawFrom, OffsetDateTime rawTo) {
    OffsetDateTime from = rawFrom.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    OffsetDateTime flooredTo = rawTo.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    return new GapDetector.Gap(from, flooredTo.equals(rawTo) ? rawTo : flooredTo.plusMinutes(1));
  }

  private void fetchAndStore(
      String exchange,
      String tradingsymbol,
      String baseInterval,
      OffsetDateTime from,
      OffsetDateTime to,
      String source) {
    InstrumentKey key = new InstrumentKey(exchange, tradingsymbol);
    for (GapDetector.Gap page : GapDetector.pages(new GapDetector.Gap(from, to))) {
      List<HistoricalCandleGateway.Candle> fetched =
          gateway.fetch(key, baseInterval, page.from().toInstant(), page.to().toInstant());
      repository.upsertAuthoritativeAll(
          fetched.stream()
              .map(
                  c ->
                      new Candle(
                          exchange, tradingsymbol, baseInterval, c.bucketStart(),
                          c.open(), c.high(), c.low(), c.close(), c.volume(), c.oi(),
                          source))
              .toList());
    }
    if (baseInterval.equals("1m")) {
      repository.refreshDerivedAggregates(from, to);
    }
  }
}

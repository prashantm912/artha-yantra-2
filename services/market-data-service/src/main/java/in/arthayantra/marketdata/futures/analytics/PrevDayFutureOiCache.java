package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-day cache of each stock future's PREVIOUS-session close open-interest, used by the Futures OI
 * Buzz to derive each tile's OI-change interpretation (Long Buildup / Short Covering / …). We do not
 * snapshot per-stock-future OI, so the baseline is fetched once a day from Kite {@code /history}
 * (1d candle carries OI; FUT fetches are per-contract, continuous=0). The warm runs on a background
 * thread so the heatmap request never blocks on ~50 historical calls — tiles render immediately with
 * a {@code null} interpretation, and the badge fills in on the next refresh once the warm completes.
 */
@Component
public class PrevDayFutureOiCache {

  private static final Logger log = LoggerFactory.getLogger(PrevDayFutureOiCache.class);

  /** A stock future to warm: its symbol (the tile key) + the resolved contract instrument key. */
  public record SymKey(String symbol, InstrumentKey key) {}

  private final HistoricalCandleGateway gateway;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "oibuzz-prevoi-warm");
            t.setDaemon(true);
            return t;
          });
  private final Map<String, Long> prevOi = new ConcurrentHashMap<>();
  private volatile LocalDate validFor;
  private volatile LocalDate warming;

  public PrevDayFutureOiCache(HistoricalCandleGateway gateway) {
    this.gateway = gateway;
  }

  /** The future's prev-session close OI, or {@code null} when the cache isn't warm for {@code today}. */
  public Long lookup(LocalDate today, String symbol) {
    return today.equals(validFor) ? prevOi.get(symbol) : null;
  }

  /** Warms the cache for {@code today} in the background (idempotent — one warm per day). */
  public synchronized void ensureWarm(LocalDate today, List<SymKey> futures) {
    if (today.equals(validFor) || today.equals(warming)) {
      return;
    }
    warming = today;
    List<SymKey> snapshot = List.copyOf(futures);
    executor.submit(() -> warm(today, snapshot));
  }

  private void warm(LocalDate today, List<SymKey> futures) {
    // Daily candles strictly before today → the last one is the previous session; its OI is the
    // prev-session close OI. A 10-day lookback covers long weekends / holidays.
    Instant from = today.minusDays(10).atStartOfDay(Ist.ZONE).toInstant();
    Instant to = today.atStartOfDay(Ist.ZONE).minusSeconds(1).toInstant();
    Map<String, Long> next = new HashMap<>();
    for (SymKey f : futures) {
      try {
        List<Candle> candles = gateway.fetch(f.key(), "1d", from, to);
        if (!candles.isEmpty()) {
          Long oi = candles.get(candles.size() - 1).oi();
          if (oi != null) {
            next.put(f.symbol(), oi);
          }
        }
      } catch (RuntimeException e) {
        // a single contract's miss must not abort the whole warm
      }
    }
    prevOi.clear();
    prevOi.putAll(next);
    validFor = today;
    warming = null;
    log.info("OI Buzz prev-day OI cache warmed for {}: {} futures", today, next.size());
  }
}

package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.options.OiInterval;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * oipulse "Futures OI Chart" (plan §futures/oi-chart) — the dual-axis OI-vs-price combo for ONE futures
 * contract over a session: candlestick PRICE (real per-interval OHLC) + the OI LINE on the left axis.
 *
 * <p>Two sources, one grid: the price candles are the contract's intraday 1m bars read cache-first via
 * the generic {@link CandleQueryService} (the identical mechanism {@code StraddleChartService} uses for
 * option legs — faithful, no new capture) and folded to the requested interval; the OI line rides the
 * EXISTING {@link FuturesSnapshotReader#series} read (untouched). Both are session-aligned to the 09:15
 * IST open via the SAME {@link #bucketStart}, so the OI value left-joins onto the candle whose bucket it
 * shares. The contract is the requested {@code expiry} else the front month (the same monthly ladder the
 * {@code FuturesPinner} pins). OI is read at the finest {@link OiInterval} (3m) and re-keyed to the candle
 * grid; on the 1-min candle option the OI line therefore floors to 3-min resolution (a disclosed, honest
 * divergence — not a fabrication). A candle bucket with no OI sample carries a null OI (the line gaps it).
 */
@Service
public class FuturesOiChartService {

  /** The NSE/BSE cash-session open — candle + OI buckets both align here. */
  private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);

  /** The oipulse oi-chart interval set in minutes (INCLUDING 1-min, the page's defining feature). */
  private static final Set<Integer> ALLOWED_MINUTES = Set.of(1, 3, 5, 10, 15, 30, 60);

  /** One interval's candle: real per-bucket OHLC + the contract's OI at that bucket (null when absent). */
  public record FutOiCandle(
      OffsetDateTime time,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      long volume,
      @Schema(types = {"integer", "null"})
      Long oi) {}

  /** The oi-chart payload: the contract header + the per-interval candle+OI series (ascending). */
  public record FutOiChart(
      String underlying,
      LocalDate expiry,
      String tradingsymbol,
      String interval,
      OffsetDateTime asOf,
      List<FutOiCandle> items) {}

  private final FuturesContractSource contracts;
  private final CandleQueryService candleQuery;
  private final FuturesSnapshotReader reader;
  private final Clock clock;

  public FuturesOiChartService(
      FuturesContractSource contracts,
      CandleQueryService candleQuery,
      FuturesSnapshotReader reader,
      Clock clock) {
    this.contracts = contracts;
    this.candleQuery = candleQuery;
    this.reader = reader;
    this.clock = clock;
  }

  /**
   * Builds the candle+OI series for the chosen contract. {@code requestedExpiry} null → the front month;
   * {@code session} null → today IST. 422 when the underlying has no listed FUT contract (or none at the
   * requested expiry).
   */
  public FutOiChart chart(
      String underlying, LocalDate requestedExpiry, int intervalMinutes, LocalDate session) {
    if (!ALLOWED_MINUTES.contains(intervalMinutes)) {
      throw new ApiException(
          400,
          ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED,
          "interval (minutes) must be one of " + ALLOWED_MINUTES.stream().sorted().toList());
    }
    LocalDate sess = session != null ? session : LocalDate.now(Ist.ZONE);
    FuturesContractSource.FutContract contract = resolveContract(underlying, requestedExpiry, sess);

    OffsetDateTime from = sess.atTime(SESSION_OPEN).atOffset(Ist.OFFSET);
    OffsetDateTime to = sess.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);

    InstrumentKey key = contract.key();
    Map<OffsetDateTime, Ohlcv> candles = aggregate(key, intervalMinutes, from, to);

    // OI line: read the finest grain (3m) and re-key each point onto the session-aligned candle grid so
    // the two tables (candles vs snapshots, different anchors/cadences) share one bucket key; last wins.
    Map<OffsetDateTime, FuturesSnapshotReader.FutPoint> oiByBucket = new LinkedHashMap<>();
    for (FuturesSnapshotReader.FutPoint p :
        reader.series(underlying, OiInterval.M3, from, to)) {
      if (p.tradingsymbol().equals(key.tradingsymbol())) {
        oiByBucket.put(bucketStart(p.bucket(), intervalMinutes), p);
      }
    }

    List<FutOiCandle> items = new ArrayList<>(candles.size());
    for (Map.Entry<OffsetDateTime, Ohlcv> e : candles.entrySet()) {
      Ohlcv c = e.getValue();
      FuturesSnapshotReader.FutPoint oi = oiByBucket.get(e.getKey()); // null → no OI sample this bucket
      items.add(
          new FutOiCandle(
              e.getKey(), c.open, c.high, c.low, c.close, c.volume, oi == null ? null : oi.oi()));
    }
    items.sort(Comparator.comparing(FutOiCandle::time));

    OffsetDateTime asOf =
        items.isEmpty() ? OffsetDateTime.now(clock) : items.get(items.size() - 1).time();
    return new FutOiChart(
        underlying, contract.expiry(), key.tradingsymbol(), intervalMinutes + "m", asOf, items);
  }

  /** The requested-expiry contract, else the front month; 422 when none is listed. */
  private FuturesContractSource.FutContract resolveContract(
      String underlying, LocalDate requestedExpiry, LocalDate session) {
    List<FuturesContractSource.FutContract> ladder =
        contracts.monthlyFutures(underlying, session); // expiry-sorted, front first
    if (requestedExpiry != null) {
      return ladder.stream()
          .filter(c -> c.expiry().equals(requestedExpiry))
          .findFirst()
          .orElseThrow(
              () ->
                  new ApiException(
                      422,
                      ErrorCodes.DATA_GAP,
                      "no FUT contract for " + underlying + " expiring " + requestedExpiry));
    }
    if (ladder.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no FUT contract for " + underlying);
    }
    return ladder.get(0);
  }

  /** Reads the contract's 1m candles cache-first and folds them into session-aligned interval buckets. */
  private Map<OffsetDateTime, Ohlcv> aggregate(
      InstrumentKey key, int intervalMinutes, OffsetDateTime from, OffsetDateTime to) {
    List<Candle> oneMin =
        new ArrayList<>(
            candleQuery.read(key.exchange(), key.tradingsymbol(), "1m", from, to).items());
    oneMin.sort(Comparator.comparing(Candle::bucket)); // close = the last bar in the bucket by time
    Map<OffsetDateTime, Ohlcv> buckets = new TreeMap<>();
    for (Candle bar : oneMin) {
      buckets.computeIfAbsent(bucketStart(bar.bucket(), intervalMinutes), k -> new Ohlcv()).fold(bar);
    }
    return buckets;
  }

  /** Floors a timestamp to the session-aligned interval boundary (open + k·N from 09:15 IST). */
  private static OffsetDateTime bucketStart(OffsetDateTime ts, int intervalMinutes) {
    OffsetDateTime open =
        ts.atZoneSameInstant(Ist.ZONE).toLocalDate().atTime(SESSION_OPEN).atOffset(Ist.OFFSET);
    long minutesSinceOpen = Duration.between(open, ts).toMinutes();
    if (minutesSinceOpen < 0) {
      return open; // pre-open prints (rare) bucket to the open
    }
    return open.plusMinutes((minutesSinceOpen / intervalMinutes) * intervalMinutes);
  }

  /** Mutable OHLCV accumulator for one interval bucket (1m bars folded in time order). */
  private static final class Ohlcv {
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;

    void fold(Candle bar) {
      if (open == null) {
        open = bar.open();
        high = bar.high();
        low = bar.low();
      } else {
        high = high.max(bar.high());
        low = low.min(bar.low());
      }
      close = bar.close();
      volume += bar.volume();
    }
  }
}
